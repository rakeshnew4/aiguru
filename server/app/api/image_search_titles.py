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

# When visual_description mentions these physics/force terms AND the LLM chose a
# basic geometry type, skip template renderers and go straight to LLM SVG builder
# so force vectors, apparatus, and angle markers are drawn precisely.
_FORCE_DIAGRAM_KEYWORDS = {
    "force", "arrow", "vector", "spring", "lens", "circuit",
    "pressure", "tension", "friction", "torque", "field",
    "acceleration", "inclined", "pendulum", "gravity", "mass",
    "velocity", "momentum", "charge", "current", "voltage",
}
_BASIC_GEOMETRY_TYPES = {"triangle", "polygon", "angle", "rectangle", "square"}


def _has_force_description(visual_desc: str) -> bool:
    lower = visual_desc.lower()
    return any(kw in lower for kw in _FORCE_DIAGRAM_KEYWORDS)


_FRAME_DEFAULTS: dict = {
    "frame_type": "concept",
    "text": "",
    "highlight": [],
    "speech": "",
    "tts_engine": "gemini",
    "voice_role": "teacher",
    "duration_ms": 2500,
    "quiz_answer": "",
    "quiz_options": [],
    "quiz_correct_index": -1,
    "quiz_model_answer": "",
    "quiz_keywords": [],
    "fill_blanks": [],
    "quiz_correct_order": [],
    "diagram_type": "",
    "data": {},
    "svg_elements": [],
    "visual_description": "",
}


def _normalize_frame(frame: dict) -> dict:
    """Fill in missing fields with defaults after sparse LLM output."""
    for key, default in _FRAME_DEFAULTS.items():
        if key not in frame:
            frame[key] = default
    return frame


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


# Images wider than this (pixels) are likely maps/scans — skip them.
_MAX_IMAGE_WIDTH = 5000
# Request thumbnails at this width from Wikimedia (instead of full source).
_THUMB_WIDTH = 800


async def search_wikimedia_images(query: str, limit: int = 10) -> List[Dict[str, str]]:
    """
    Async search for Wikimedia Commons images.
    Returns list of {"title": ..., "url": ..., "orig_width": int} dicts.
    url is a thumbnail at _THUMB_WIDTH px (faster to load than the original).
    Images with original width > _MAX_IMAGE_WIDTH are skipped (maps, scans, etc.).
    """
    api_url = "https://commons.wikimedia.org/w/api.php"
    params = {
        "action": "query",
        "generator": "search",
        "gsrsearch": query,
        "gsrlimit": limit,
        "gsrnamespace": 6,  # File namespace
        "prop": "imageinfo",
        "iiprop": "url|dimensions|mime|size",
        "iiurlwidth": str(_THUMB_WIDTH),  # Ask Wikimedia for a pre-scaled thumbnail URL
        "format": "json",
    }

    try:
        client = get_http_client()
        response = await client.get(api_url, params=params)
        response.raise_for_status()
        data = response.json()

        results = []
        if "query" in data and "pages" in data["query"]:
            for page in data["query"]["pages"].values():
                if "imageinfo" in page:
                    info = page["imageinfo"][0]
                    orig_url = info.get("url", "")
                    orig_url_lower = orig_url.lower()
                    mime = info.get("mime", "").lower()
                    is_image = (
                        orig_url_lower.endswith((".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"))
                        or mime.startswith(("image/",))
                    )
                    if not (is_image and orig_url):
                        continue

                    orig_width = info.get("width", 0) or 0
                    # Skip extreme-dimension source files (maps, historical scans, blueprints)
                    if orig_width > _MAX_IMAGE_WIDTH:
                        logger.debug(
                            "Skipping oversized image (%dpx wide): %s", orig_width, page["title"]
                        )
                        continue

                    # Prefer the Wikimedia-generated thumbnail URL; fall back to original
                    thumb_url = info.get("thumburl") or orig_url

                    results.append({
                        "title": page["title"],
                        "url": thumb_url,
                        "orig_width": orig_width,
                    })

        logger.info("Found %d images for query: %s", len(results), query[:50])
        return results

    except httpx.HTTPError as e:
        logger.error(f"Wikimedia API error for query '{query}': {e}")
        return []
    except Exception as e:
        logger.error(f"Unexpected error in image search: {e}")
        return []


def _pick_by_word_overlap(steps: list, all_candidates: List[Dict[str, str]]) -> Dict[int, str]:
    """
    Fast no-LLM image picker using word-overlap scoring.
    Returns {step_index: direct_image_url}.
    """
    title_to_url: Dict[str, str] = {}
    for item in all_candidates:
        if isinstance(item, dict):
            t = item.get("title", "").replace("File:", "").strip()
            u = item.get("url", "")
            if t and u:
                title_to_url[t] = u
    if not title_to_url:
        return {}
    raw_titles = list(title_to_url.keys())
    result: Dict[int, str] = {}
    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        score = step.get("image_show_confidencescore") or 0
        if score < 0.35:
            continue
        desc = (step.get("image_description") or "").strip()
        if not desc:
            continue
        best = _best_title_match(desc, raw_titles)
        if best:
            url = title_to_url.get(best.replace("File:", "").strip(), "")
            if url:
                result[i] = url
    return result


def _pick_titles_sync(steps: list, all_candidates: List[Dict[str, str]]) -> Dict[int, str]:
    """
    LLM-based image title picker (kept for reference — not called in production).
    all_candidates: list of {"title": ..., "url": ...} dicts.
    Returns {step_index: direct_image_url}.
    """
    from app.services.llm_service import generate_response

    # Build title→url lookup
    title_to_url: Dict[str, str] = {}
    for item in all_candidates:
        if isinstance(item, dict):
            title = item.get("title", "").replace("File:", "").strip()
            url = item.get("url", "")
            if title and url:
                title_to_url[title] = url

    if not title_to_url:
        return {}

    # Gather steps that actually need an image
    needs_image = []
    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        score = step.get("image_show_confidencescore") or 0
        if score < 0.35:
            continue
        desc = (step.get("image_description") or "").strip()
        if not desc:
            continue
        # Pull visual_description from the first diagram frame of this step
        frames = step.get("frames", [])
        vis_desc = next(
            (
                (f.get("visual_description") or "").strip()
                for f in frames
                if isinstance(f, dict) and f.get("frame_type") == "diagram"
            ),
            "",
        )
        needs_image.append({
            "idx": i,
            "title": (step.get("title") or "")[:60],
            "desc": desc,
            "vis": vis_desc[:200],
        })

    if not needs_image:
        return {}

    clean_titles = list(title_to_url.keys())[:30]

    steps_text = "\n".join(
        f'[{s["idx"]}] Step "{s["title"]}" — topic: "{s["desc"]}"'
        + (f'\n        Visual layout: "{s["vis"]}"' if s["vis"] else "")
        for s in needs_image
    )
    picker_prompt = (
        "You are selecting the best matching Wikimedia Commons image for each blackboard lesson step.\n"
        "Pick ONE title per step from the candidates list. If no candidate is a good match, use null.\n"
        "When a Visual layout is provided, prefer images that match the described visual structure\n"
        "(force arrows, cross-sections, textbook diagrams) over generic photos of the same topic.\n"
        "Output ONLY valid JSON mapping step index (string key) to the EXACT title string or null.\n\n"
        f"Steps needing images:\n{steps_text}\n\n"
        "Available Wikimedia titles:\n"
        + "\n".join(f"- {t}" for t in clean_titles)
        + '\n\nOutput example: {"0": "Photosynthesis diagram.svg", "3": null, "5": "Newton laws.png"}'
    )
    try:
        raw = generate_response(picker_prompt, [], tier="faster", call_name="image_picker")
        text = (raw.get("text") or "").strip()
        parsed = extract_json_safe(text)
        result: Dict[int, str] = {}
        for k, v in parsed.items():
            try:
                idx = int(k)
                if v and isinstance(v, str):
                    clean_title = v.replace("File:", "").strip()
                    url = title_to_url.get(clean_title, "")
                    if url:
                        result[idx] = url
                        logger.info("Step %d → image URL resolved: %s", idx, url[:80])
            except (ValueError, TypeError):
                pass
        logger.info("LLM image picker assigned %d image URLs", len(result))
        return result
    except Exception as exc:
        logger.warning("LLM image picker failed (%s) — falling back to word-overlap", exc)
        # Fallback: word-overlap scoring
        raw_titles = [item.get("title", "") for item in all_candidates if isinstance(item, dict)]
        fallback: Dict[int, str] = {}
        for item in needs_image:
            best_title = _best_title_match(item["desc"], raw_titles)
            if best_title:
                clean = best_title.replace("File:", "").strip()
                url = title_to_url.get(clean, "")
                if url:
                    fallback[item["idx"]] = url
        return fallback


async def get_titles(query: str, extra_candidates: Optional[List[str]] = None, animations_enabled: bool = True) -> str:
    """
    Process BB JSON and attach the best Wikimedia image title to each high-confidence step.
    Uses LLM-based selection (much more accurate than word-overlap).

    extra_candidates:   pre-fetched titles from the BB planner's image_search_terms
                        (these arrive while the main BB LLM is running, so they're free).
    animations_enabled: when False, build_animated_svg() strips all SMIL animations so
                        diagrams are rendered as static images (lower credit cost).
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
        from app.utils.svg_llm_builder import build_llm_svg
        from app.utils.js_engine import build_js_diagram_html
        from app.utils.diagram_router import classify_diagram_need
        from app.services.enrichment_service import build_enrichment_tasks

        loop = asyncio.get_event_loop()

        # ── Phase 1: Launch enrichment tasks + wikimedia searches in parallel ──
        # Enrichment: diagram data enrichment (faster model).
        # Wikimedia: per-step image searches.
        # ALL run concurrently → near-zero extra wall-clock time.

        enr_futs, diagram_refs = build_enrichment_tasks(data["steps"], loop)

        # Collect wikimedia descriptions
        descriptions = []
        for step in data["steps"]:
            if isinstance(step, dict):
                desc = (step.get("image_description") or "").strip()
                score = step.get("image_show_confidencescore") or 0
                if desc and len(desc) > 3 and score >= 0.35:
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

            for frame in step.get("frames", []):
                if not isinstance(frame, dict):
                    continue
                _normalize_frame(frame)
                if frame.get("frame_type") != "diagram":
                    continue

                html = ""
                d_type = (frame.get("diagram_type") or "").strip()
                d_data = frame.get("data") or {}
                visual_desc = (frame.get("visual_description") or "").strip()

                # ── Custom intent-only type: route directly to LLM SVG builder ──
                if d_type.lower() == "custom":
                    intent = d_data.get("intent", "") if isinstance(d_data, dict) else ""
                    step_title = step.get("title", "")
                    frame_speech = frame.get("speech", "")
                    html = build_llm_svg(
                        diagram_type="custom",
                        data={},
                        topic=step_title,
                        speech=frame_speech,
                        visual_description=intent or visual_desc or step_title,
                    )
                    if html:
                        logger.info("Built LLM SVG (custom intent) for step '%s'", step_title)
                    if html:
                        frame["svg_html"] = html
                    frame.pop("svg_elements", None)
                    frame.pop("diagram_type", None)
                    frame.pop("data", None)
                    frame.pop("visual_description", None)
                    continue

                # ── Auto-classify only when LLM set NEITHER diagram_type NOR svg_elements ──
                # If LLM already planned svg_elements, trust that — don't override it.
                if not d_type and not frame.get("svg_elements"):
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

                # ── Force/physics override: skip templates when visual_description ─
                # describes force vectors, arrows, or apparatus but LLM chose a basic
                # geometry type — go straight to LLM SVG builder for accurate drawing.
                force_override = (
                    visual_desc
                    and d_type.lower() in _BASIC_GEOMETRY_TYPES
                    and _has_force_description(visual_desc)
                )
                if force_override:
                    logger.info(
                        "Force override: bypassing templates for type='%s', step='%s' (visual_desc contains physics terms)",
                        d_type, step.get("title", ""),
                    )

                if d_type and not force_override:
                    step_title  = step.get("title", "")
                    frame_speech = frame.get("speech", "")

                    # ── Atom: compact proven JS+SVG animation (~5KB) ──────────
                    if d_type.lower() == "atom":
                        html = build_atom_html(d_data)
                        if html:
                            logger.info("Built atom JS animation for step '%s'", step_title)

                    # ── JS engine: procedural, continuous animations ───────────
                    # Handles: wave, solar_system, cycle, flow, plant, sun,
                    # labeled_diagram, cell, comparison, etc.
                    # Runs forever via requestAnimationFrame — no SMIL freeze.
                    if not html:
                        html = build_js_diagram_html(d_type, d_data)
                        if html:
                            logger.info(
                                "Built JS engine animation (type=%s) for step '%s'",
                                d_type, step_title,
                            )

                    # ── LLM SVG: complex anatomy / structures not in engine ───
                    # Heart chambers, eye cross-section, circuit schematics, etc.
                    if not html:
                        html = build_llm_svg(
                            diagram_type=d_type,
                            data=d_data,
                            topic=step_title,
                            speech=frame_speech,
                            visual_description=visual_desc,
                        )

                    # ── Fallback: Python SMIL builder (geometric, no LLM) ────
                    if not html:
                        shapes = build_from_diagram_type(d_type, d_data)
                        if shapes:
                            html = build_animated_svg(shapes, static=not animations_enabled)
                            if html:
                                logger.info(
                                    "Built SMIL svg_html (type=%s) for step '%s' [fallback, static=%s]",
                                    d_type, step_title, not animations_enabled,
                                )

                # ── Force override path: LLM SVG with visual_description ──────
                if not html and force_override:
                    step_title = step.get("title", "")
                    frame_speech = frame.get("speech", "")
                    html = build_llm_svg(
                        diagram_type=d_type,
                        data=d_data,
                        topic=step_title,
                        speech=frame_speech,
                        visual_description=visual_desc,
                    )
                    if html:
                        logger.info(
                            "Built LLM SVG (force override, type=%s) for step '%s'",
                            d_type, step_title,
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
                        html = build_animated_svg(elems_json, static=not animations_enabled)
                        if html:
                            logger.info("Built legacy svg_html for step '%s' [static=%s]", step.get("title", ""), not animations_enabled)

                if html:
                    frame["svg_html"] = html

                frame.pop("svg_elements", None)
                frame.pop("diagram_type", None)
                frame.pop("data", None)
                frame.pop("visual_description", None)

        # ── Phase 4: Image title selection from wikimedia results ─────────────
        # all_candidates: list of {"title": ..., "url": ...} dicts
        all_candidates: List[Dict[str, str]] = []
        for result in per_step_results:
            if isinstance(result, list):
                all_candidates.extend(result)
        if extra_candidates:
            # extra_candidates may be dicts (from _prefetch_wikimedia) or legacy strings
            for item in extra_candidates:
                if isinstance(item, dict):
                    all_candidates.append(item)
        # Deduplicate by URL
        seen_urls: set = set()
        deduped: List[Dict[str, str]] = []
        for item in all_candidates:
            url = item.get("url", "")
            if url and url not in seen_urls:
                seen_urls.add(url)
                deduped.append(item)
        all_candidates = deduped

        # Save original LLM search phrases before we overwrite with URLs.
        # When no Wikimedia URL is found we fall back to the phrase so Android
        # always has something (phrase → it can search itself; URL → show directly).
        original_descs: Dict[int, str] = {
            i: (step.get("image_description") or "").strip()
            for i, step in enumerate(data["steps"])
            if isinstance(step, dict)
        }
        # Also ensure every high-confidence step without a description gets the step title.
        for i, step in enumerate(data["steps"]):
            if not isinstance(step, dict):
                continue
            score = step.get("image_show_confidencescore") or 0
            if score >= 0.35 and not original_descs.get(i):
                fallback = (step.get("title") or "").strip()
                if fallback:
                    step["image_description"] = fallback
                    original_descs[i] = fallback

        if not all_candidates:
            logger.info("No Wikimedia candidates found — keeping original search phrases")
            return json.dumps(data)

        # Build URL→orig_width lookup so we can apply size-based score cap later
        url_to_width: Dict[str, int] = {
            item["url"]: item.get("orig_width", 0)
            for item in all_candidates
            if isinstance(item, dict) and item.get("url")
        }
        # Word-overlap matching — no LLM call, saves ~1 call/session.
        # _pick_titles_sync (LLM path) kept for reference but not called here.
        picks = _pick_by_word_overlap(data["steps"], all_candidates)

        for i, step in enumerate(data["steps"]):
            if not isinstance(step, dict):
                continue
            score = step.get("image_show_confidencescore") or 0
            if score < 0.35:
                continue
            if i in picks:
                picked_url = picks[i]
                # Store the direct image URL — Android loads it without re-querying Wikimedia
                step["image_description"] = picked_url
                logger.info("Step %d → image URL: %s", i, picked_url[:80])
                # Cap score to 0.5 (tap-to-view) when original image is large (>3000px wide).
                # Large images load slowly and look oversized on mobile.
                orig_w = url_to_width.get(picked_url, 0)
                if orig_w > 3000 and score > 0.5:
                    step["image_show_confidencescore"] = 0.5
                    logger.info(
                        "Step %d: capped score to 0.5 (image orig_width=%dpx > 3000)", i, orig_w
                    )
            else:
                # No Wikimedia match — restore the original LLM phrase so Android always
                # has image_description present (never null/missing in the final JSON).
                orig = original_descs.get(i, "")
                if orig:
                    step["image_description"] = orig
                    logger.info("Step %d: no image match, keeping phrase: %s", i, orig[:60])

        # ── Guarantee step 2 (index 1) has a visual (diagram or image) ──────────
        if len(data["steps"]) > 1:
            step1 = data["steps"][1]
            if isinstance(step1, dict):
                has_diagram = any(
                    isinstance(f, dict) and f.get("frame_type") == "diagram" and f.get("svg_html")
                    for f in step1.get("frames", [])
                )
                has_image = bool(
                    (step1.get("image_description") or "").startswith("https://")
                )
                if not has_diagram and not has_image:
                    # Inject image search from step title as fallback
                    title = (step1.get("title") or "").strip()
                    if title:
                        step1["image_description"] = title
                        step1["image_show_confidencescore"] = 0.6
                        logger.info("Step 1: injected image search for '%s' to guarantee visual", title)

        return json.dumps(data)

    except Exception as e:
        logger.exception("Error in get_titles: %s", e)
        return query