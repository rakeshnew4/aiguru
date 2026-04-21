import hashlib
import httpx
import json
import re
import asyncio
from typing import List, Dict, Optional
from app.core.logger import get_logger

logger = get_logger(__name__)

# Shared HTTP client for connection pooling
_http_client: Optional[httpx.AsyncClient] = None


def get_http_client() -> httpx.AsyncClient:
    """Get or create shared async HTTP client with connection pooling."""
    global _http_client
    if _http_client is None:
        _http_client = httpx.AsyncClient(
            timeout=30.0,
            limits=httpx.Limits(max_keepalive_connections=20, max_connections=100),
            headers={
                "User-Agent": "AI-Image-Pipeline/1.0 (contact: rakeshkolipaka4@gmail.com)"
            },
        )
    return _http_client


# Generic visual words that should NOT drive relevance matching.
# Without this, "diagram" alone could match any image with "diagram" in its title.
_GENERIC_IMAGE_WORDS = {
    "diagram", "image", "picture", "photo", "figure", "illustration",
    "chart", "graph", "file", "svg", "png", "jpg", "jpeg", "gif",
}


def _best_title_match(description: str, titles: List[str]) -> Optional[str]:
    """
    Pick the Wikimedia title with the highest content-word overlap against the
    image description. Generic visual words (diagram, image, etc.) are excluded
    from scoring so only subject-specific terms (e.g. "photosynthesis", "Newton")
    drive the match. Requires >= 50% content-word overlap to accept a result.
    Returns the matched title (without 'File:' prefix) or None.
    """
    all_words = set(re.sub(r"[^a-z0-9]", " ", description.lower()).split())
    # Score only on content-specific words, not generic visual labels
    content_words = all_words - _GENERIC_IMAGE_WORDS
    if not content_words:
        content_words = all_words  # fallback: all generic words, use them
    if not content_words:
        return None

    best_title: Optional[str] = None
    best_score = 0.0
    for raw_title in titles:
        clean = re.sub(r"\.\w{2,5}$", "", raw_title.replace("File:", ""))
        title_words = set(re.sub(r"[^a-z0-9]", " ", clean.lower()).split())
        # Fraction of subject-specific description words found in this title
        score = len(content_words & title_words) / len(content_words)
        if score > best_score:
            best_score, best_title = score, raw_title.replace("File:", "")

    # Require at least 50% content-word match to avoid false positives
    # (e.g. "rational system" should NOT match "Hack Computer CPU Block Diagram")
    return best_title if best_score >= 0.5 else None


def extract_json_safe(text: str) -> Dict:
    """
    Robust JSON extractor with multiple fallback strategies.
    Handles markdown code blocks, plain JSON, and malformed LLM output.
    """
    if not text or not text.strip():
        raise ValueError("Empty input text")

    text = text.strip()

    # Strategy 1: Try direct JSON parse first (fastest)
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Strategy 2: Extract from ```json block
    match = re.search(r"```json\s*(\{.*?\})\s*```", text, re.DOTALL | re.IGNORECASE)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError as e:
            logger.warning(f"Failed to parse JSON from code block: {e}")

    # Strategy 3: Extract from ``` block (no language specified)
    match = re.search(r"```\s*(\{.*?\})\s*```", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass

    # Strategy 4: Find any JSON-like structure
    match = re.search(r"(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\})", text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            pass

    # Strategy 5: Try to fix common JSON issues
    try:
        # Replace single quotes with double quotes
        fixed = text.replace("'", '"')
        return json.loads(fixed)
    except json.JSONDecodeError:
        pass

    logger.error(f"All JSON extraction strategies failed. Input text: {text[:200]}")
    raise ValueError(f"No valid JSON found in text. Preview: {text[:200]}")


async def search_wikimedia_images(query: str, limit: int = 10) -> List[str]:
    """Async search for Wikimedia Commons images. Returns list of matching image titles."""
    url = "https://commons.wikimedia.org/w/api.php"
    params = {
        "action": "query",
        "generator": "search",
        "gsrsearch": query,
        "gsrlimit": limit,
        "gsrnamespace": 6,  # File namespace
        "prop": "imageinfo",
        "iiprop": "url|dimensions|mime",
        "format": "json",
    }

    try:
        client = get_http_client()
        response = await client.get(url, params=params)
        response.raise_for_status()
        data = response.json()

        titles = []
        if "query" in data and "pages" in data["query"]:
            for page in data["query"]["pages"].values():
                if "imageinfo" in page:
                    info = page["imageinfo"][0]
                    # Filter for common image formats
                    if info.get("url", "").lower().endswith(
                        (".png", ".jpg", ".jpeg", ".gif")
                    ):
                        titles.append(page["title"])

        logger.info(f"Found {len(titles)} images for query: {query[:50]}")
        return titles

    except httpx.HTTPError as e:
        logger.error(f"Wikimedia API error for query '{query}': {e}")
        return []
    except Exception as e:
        logger.error(f"Unexpected error in image search: {e}")
        return []


def _pick_titles_sync(steps: list, all_candidates: list) -> Dict[int, str]:
    """
    Sync LLM-based image title picker.
    For each high-confidence step, asks the 'faster' model to pick the best
    Wikimedia title from all_candidates.  Falls back to word-overlap on failure.
    Returns {step_index: title_string}.
    """
    from app.services.llm_service import generate_response

    # Gather steps that actually need an image
    needs_image = []
    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        score = step.get("image_show_confidencescore") or 0
        if score < 0.5:
            continue
        desc = (step.get("image_description") or "").strip()
        if not desc:
            continue
        needs_image.append({"idx": i, "title": (step.get("title") or "")[:60], "desc": desc})

    if not needs_image:
        return {}

    clean = [t.replace("File:", "").strip() for t in all_candidates if t and isinstance(t, str)][:30]
    if not clean:
        return {}

    steps_text = "\n".join(
        f'[{s["idx"]}] Step "{s["title"]}" — image needed for: "{s["desc"]}"'
        for s in needs_image
    )
    picker_prompt = (
        "You are selecting the best matching Wikimedia Commons image for each blackboard lesson step.\n"
        "Pick ONE title per step from the candidates list. If no candidate is a good match, use null.\n"
        "Output ONLY valid JSON mapping step index (string key) to the EXACT title string or null.\n\n"
        f"Steps needing images:\n{steps_text}\n\n"
        "Available Wikimedia titles:\n"
        + "\n".join(f"- {t}" for t in clean)
        + '\n\nOutput example: {"0": "Photosynthesis diagram.svg", "3": null, "5": "Newton laws.png"}'
    )
    try:
        raw = generate_response(picker_prompt, [], tier="faster")
        text = (raw.get("text") or "").strip()
        parsed = extract_json_safe(text)
        result: Dict[int, str] = {}
        for k, v in parsed.items():
            try:
                idx = int(k)
                if v and isinstance(v, str):
                    result[idx] = v.replace("File:", "").strip()
            except (ValueError, TypeError):
                pass
        logger.info("LLM image picker assigned %d images", len(result))
        return result
    except Exception as exc:
        logger.warning("LLM image picker failed (%s) — falling back to word-overlap", exc)
        # Fallback: word-overlap scoring (original algorithm)
        fallback: Dict[int, str] = {}
        for item in needs_image:
            best = _best_title_match(item["desc"], all_candidates)
            if best:
                fallback[item["idx"]] = best.replace("File:", "").strip()
        return fallback


async def get_titles(query: str, extra_candidates: Optional[List[str]] = None) -> str:
    """
    Process BB JSON and attach the best Wikimedia image title to each high-confidence step.
    Uses LLM-based selection (much more accurate than word-overlap).

    extra_candidates: pre-fetched titles from the BB planner's image_search_terms
                      (these arrive while the main BB LLM is running, so they're free).
    """
    try:
        try:
            data = extract_json_safe(query)
        except Exception as e:
            logger.error("Failed to parse BB JSON: %s — returning original", e)
            return query

        if "steps" not in data or not isinstance(data["steps"], list):
            return query

        from app.utils.svg_builder import build_animated_svg, build_from_diagram_type, build_atom_html
        from app.utils.diagram_router import classify_diagram_need
        from app.services.enrichment_service import build_enrichment_tasks

        loop = asyncio.get_event_loop()

        # ── Phase 1: Launch enrichment tasks + wikimedia searches in parallel ──
        # Enrichment: diagram data enrichment + quiz answer validation (faster model).
        # Wikimedia: per-step image searches.
        # ALL run concurrently → near-zero extra wall-clock time.

        enr_futs, diagram_refs, quiz_refs = build_enrichment_tasks(data["steps"], loop)

        # Collect wikimedia descriptions
        descriptions = []
        for step in data["steps"]:
            if isinstance(step, dict):
                desc = (step.get("image_description") or "").strip()
                score = step.get("image_show_confidencescore") or 0
                if desc and len(desc) > 3 and score > 0.5:
                    descriptions.append(desc)
                else:
                    descriptions.append("")
            else:
                descriptions.append("")

        async def search_or_skip(desc: str) -> List[str]:
            if not desc:
                return []
            try:
                return await search_wikimedia_images(desc, limit=8)
            except Exception as e:
                logger.warning("Image search failed for '%s': %s", desc, e)
                return []

        wiki_futs = [search_or_skip(d) for d in descriptions]

        # Run everything in parallel: [enr_fut_0, ..., wiki_fut_0, ...]
        all_results = await asyncio.gather(
            *enr_futs, *wiki_futs,
            return_exceptions=True,
        )

        enr_results = all_results[:len(enr_futs)]
        per_step_results = all_results[len(enr_futs):]

        # ── Phase 2: Apply enrichment results ─────────────────────────────────
        # Diagram data: update frame["data"] before SVG building
        for i, (step, frame) in enumerate(diagram_refs):
            r = enr_results[i]
            if isinstance(r, dict) and r:
                frame["data"] = r

        # Quiz answers: update quiz_correct_index
        quiz_offset = len(diagram_refs)
        for i, (step, frame) in enumerate(quiz_refs):
            r = enr_results[quiz_offset + i]
            if isinstance(r, int) and 0 <= r < len(frame.get("quiz_options", [])):
                frame["quiz_correct_index"] = r

        # ── Phase 3: Build SVG diagrams (using enriched data) ─────────────────
        for step in data["steps"]:
            if not isinstance(step, dict):
                continue
            has_diagram = any(
                isinstance(f, dict) and f.get("frame_type") == "diagram"
                for f in step.get("frames", [])
            )
            if not has_diagram:
                continue

            # Suppress Wikimedia image for diagram steps — the SVG IS the visual
            step["image_show_confidencescore"] = 0.0
            step.pop("image_description", None)

            for frame in step.get("frames", []):
                if not isinstance(frame, dict) or frame.get("frame_type") != "diagram":
                    continue

                html = ""
                d_type = (frame.get("diagram_type") or "").strip()
                d_data = frame.get("data") or {}

                # ── Auto-classify if LLM omitted diagram_type ────────────────
                if not d_type:
                    step_title = step.get("title", "")
                    step_speech = frame.get("speech", "")
                    _q = f"{step_title} {step_speech}"
                    decision = classify_diagram_need(_q, subject_hint="")
                    if decision.needed and decision.diagram_type:
                        d_type = decision.diagram_type
                        d_data = {}
                        logger.info(
                            "diagram_router auto-classified type='%s' (conf=%.2f) for step '%s'",
                            d_type, decision.confidence, step_title,
                        )

                if d_type:
                    # ── Atom: JS physics engine (elliptical orbits, glow, phase reveal) ──
                    if d_type.lower() in ("atom",):
                        html = build_atom_html(d_data)
                        if html:
                            logger.info(
                                "Built atom JS animation for step '%s'",
                                step.get("title", ""),
                            )
                    # Path 1: all other structured diagram_types → SMIL SVG
                    if not html:
                        shapes = build_from_diagram_type(d_type, d_data)
                        if shapes:
                            html = build_animated_svg(shapes)
                            if html:
                                logger.info(
                                    "Built svg_html (type=%s enriched=%s) for step '%s'",
                                    d_type, bool(d_data), step.get("title", ""),
                                )

                if not html:
                    # Path 2: legacy raw svg_elements fallback
                    svg_elems = frame.get("svg_elements")
                    if svg_elems:
                        elems_json = (
                            json.dumps(svg_elems)
                            if isinstance(svg_elems, list)
                            else str(svg_elems)
                        )
                        html = build_animated_svg(elems_json)
                        if html:
                            logger.info("Built legacy svg_html for step '%s'", step.get("title", ""))

                if html:
                    frame["svg_html"] = html

                frame.pop("svg_elements", None)
                frame.pop("diagram_type", None)
                frame.pop("data", None)

        # ── Phase 4: Image title selection from wikimedia results ─────────────
        all_candidates: List[str] = []
        for titles in per_step_results:
            if isinstance(titles, list):
                all_candidates.extend(titles)
        if extra_candidates:
            all_candidates.extend(extra_candidates)
        all_candidates = list(dict.fromkeys(all_candidates))

        if not all_candidates:
            logger.info("No Wikimedia candidates found — clearing image descriptions")
            for step in data["steps"]:
                if isinstance(step, dict) and step.get("image_description"):
                    step.pop("image_description", None)
            return json.dumps(data)

        picks: Dict[int, str] = await loop.run_in_executor(
            None, lambda: _pick_titles_sync(data["steps"], all_candidates)
        )

        for i, step in enumerate(data["steps"]):
            if not isinstance(step, dict):
                continue
            score = step.get("image_show_confidencescore") or 0
            if score <= 0:
                continue
            if i in picks:
                step["image_description"] = picks[i]
                logger.info("Step %d → image: %s", i, picks[i])
            elif step.get("image_description"):
                step.pop("image_description", None)
                logger.info("Step %d: no image match, description cleared", i)

        return json.dumps(data)

    except Exception as e:
        logger.exception("Error in get_titles: %s", e)
        return query