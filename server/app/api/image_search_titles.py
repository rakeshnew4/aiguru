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
    """
    Async search for Wikimedia Commons images.
    Returns list of matching image titles.
    """
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


async def get_titles(query: str) -> str:
    """
    Main async function to process query and find matching image titles.
    Runs image searches in parallel for better performance.
    ROBUST: Never fails - returns original query on any error.
    """
    try:
        # Parse input JSON
        try:
            data = extract_json_safe(query)
        except Exception as e:
            logger.error(f"Failed to parse input JSON: {e}. Returning original query.")
            return query

        if "steps" not in data or not isinstance(data["steps"], list):
            logger.error("Invalid input: missing 'steps' array")
            return query  # Return original if structure is wrong

        # Collect all image descriptions
        descriptions = []
        for step in data["steps"]:
            if isinstance(step, dict):
                image_desc = step.get("image_description")
                # Skip None, empty, or non-string values
                if image_desc and isinstance(image_desc, str):
                    if step.get("image_show_confidencescore", 0) > 0.5:
                        desc = image_desc.strip()
                        # Skip very generic or empty descriptions
                        if desc and len(desc) > 3 and desc.lower() not in ["image", "picture", "photo"]:
                            descriptions.append(desc)
                        else:
                            descriptions.append("")  # Placeholder to maintain index alignment
                    else:
                        descriptions.append("")  # Placeholder for low confidence
                else:
                    descriptions.append("")  # Placeholder for None/missing descriptions

        if not any(descriptions):  # Check if all are empty
            logger.info("No valid image descriptions found in query")
            return query

        # Run searches in parallel (return empty list for blank descriptions)
        async def search_or_skip(desc: str) -> List[str]:
            if desc:
                try:
                    return await search_wikimedia_images(desc, limit=10)
                except Exception as e:
                    logger.warning(f"Image search for '{desc}' failed: {e}. Skipping.")
                    return []
            return []
        
        search_tasks = [search_or_skip(desc) for desc in descriptions]
        all_titles = await asyncio.gather(*search_tasks)

        # Match each step's image_description to the best Wikimedia title using
        # word-overlap scoring.  No LLM call needed — descriptions are already
        # short (1-2 keywords) and the search results are the ground truth.
        if "steps" in data:
            for i, step in enumerate(data["steps"]):
                if not isinstance(step, dict):
                    continue
                titles_for_step = all_titles[i] if i < len(all_titles) else []
                image_desc = step.get("image_description")
                if not image_desc or not isinstance(image_desc, str):
                    continue
                desc = image_desc.strip()
                if not desc or len(desc) <= 3:
                    continue
                best = _best_title_match(desc, titles_for_step)
                if best:
                    step["image_description"] = best
                    logger.info("Step %d: matched '%s' → '%s'", i, desc, best)
                else:
                    step.pop("image_description", None)
                    logger.info("Step %d: no match for '%s', removed image", i, desc)

        return json.dumps(data)

    except Exception as e:
        logger.exception(f"Error in get_titles: {e}")
        return query  # Always return something valid