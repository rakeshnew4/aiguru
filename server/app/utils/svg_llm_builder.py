"""
svg_llm_builder.py — LLM-driven SVG diagram generator.

Instead of generating Python code and executing it (security risk), we ask
the LLM to output raw SVG XML directly.  Same expressiveness — the LLM can
draw any shape, anatomy, apparatus or structure as SVG paths/ellipses/etc.
We validate the XML, wrap it in the standard HTML page, and return it.

Retry logic: up to MAX_RETRIES attempts with fresh prompts.

Usage:
    from app.utils.svg_llm_builder import build_llm_svg

    html = build_llm_svg(
        diagram_type = "labeled_diagram",
        data         = {"label": "Heart", "parts": ["Left Ventricle", ...]},
        topic        = "The Human Heart",
        speech       = "The heart has four chambers...",
    )
    # Returns full HTML string, or "" on failure.
"""
from __future__ import annotations

import json
import logging
import re
import xml.etree.ElementTree as ET

logger = logging.getLogger(__name__)

MAX_RETRIES = 3

# ── HTML wrapper — identical CSS to SMIL diagrams ────────────────────────────
_HTML_PREFIX = (
    '<!DOCTYPE html><html><head>'
    '<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">'
    '<style>*{margin:0;padding:0}body{background:#1A2B1A;display:flex;align-items:center;'
    'justify-content:center;width:100%;height:100vh}svg{max-width:100%}</style>'
    '</head><body>'
)
_HTML_SUFFIX = '</body></html>'


def _wrap_svg(svg: str) -> str:
    # Attribute: viewBox="0 0 400 300" width="100%" xmlns="http://www.w3.org/2000/svg"
    # The LLM may omit xmlns:xlink; add it if needed for animateMotion mpath
    _XLINK_NS = 'xmlns:xlink="http://www.w3.org/1999/xlink"'
    if 'xlink:href' in svg and _XLINK_NS not in svg:
        svg = svg.replace('<svg ', f'<svg {_XLINK_NS} ', 1)
    return _HTML_PREFIX + svg + _HTML_SUFFIX

# ── System prompt — static, cache-eligible (large, sent as role=system) ──────
_SYSTEM_PROMPT = """\
You are an SVG diagram artist for an educational app. Draw accurate diagrams as raw SVG.

HARD RULES:
1. Output ONLY the <svg> element. No markdown, no ```, no comments, no JS, no separators.
2. First child: <rect width="400" height="300" fill="#1A2B1A"/>
3. Attributes: viewBox="0 0 400 300" width="100%" xmlns="http://www.w3.org/2000/svg"
4. Keep all content inside x=8..392, y=8..292. Labels max 22 chars, no overlap.
5. Max 40 SVG elements total.

COLORS: #4FC3F7 blue, #FF6B6B red, #81C784 green, #F0EDD0 white, #FFB74D orange,
  #F5E3A0 yellow, #8BAB8B grey, #FFD700 gold, #CE93D8 purple.
TEXT: font-family="serif" fill="#F0EDD0". Titles font-size="13" font-weight="bold". Labels font-size="11".

ANIMATION (Phase 1 only — staggered draw-on reveal, plays once):
  Shapes: stroke-dashoffset PATHLEN to 0, dur="0.6s", fill="freeze", stagger begin +0.3s each.
  Text: opacity 0 to 1, dur="0.35s", fill="freeze".

DRAW REAL STRUCTURES (never flowcharts with boxes/arrows):
  Biology/Anatomy: organ shapes with ellipse+path curves. Heart: 4 chambers + aorta curves.
  Cell: membrane, nucleus, mitochondria as distinct shapes. Use path d="M...C...Z" for curves.
  Physics: resistor zigzag paths, capacitor parallel lines, actual lens/apparatus shapes.
  Chemistry: atomic shells as circles, bond lines.
  Fill-opacity="0.25" on main structures. Stroke-width 2-3 main, 1.5 secondary.
  Minimum: main structure + 4 labeled parts + title.
"""

_USER_PROMPT_TEMPLATE = """\
Draw this educational diagram as SVG:

  Diagram type : {diagram_type}
  Topic/title  : {topic}
  Context      : {speech}
  Data hints   : {data_json}
{visual_layout_line}
Draw "{topic}" accurately — show the REAL structure/anatomy/physics, not just abstract shapes.
Animate each part appearing sequentially.  Output ONLY the <svg> element.\
"""


def _extract_svg(text: str) -> str:
    """Extract the first <svg …>…</svg> block from raw LLM output.

    Handles:
    - Bare <svg> (ideal)
    - Inside ```svg / ```xml / ```html code fences
    - Inside a full <!DOCTYPE html> / <html><body>…</body></html> wrapper
    """
    # Strip markdown code fences first
    cleaned = re.sub(r"^```[a-zA-Z]*\s*", "", text.strip())
    cleaned = re.sub(r"\s*```\s*$", "", cleaned)

    # Try to find <svg>…</svg> in the (possibly unwrapped) text
    m = re.search(r"(<svg[\s\S]*?</svg\s*>)", cleaned, re.IGNORECASE)
    return m.group(1).strip() if m else ""


_AMP_RE = re.compile(r"&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)")


def _repair_svg_xml(svg: str) -> str:
    """Attempt common LLM XML repairs before giving up on validation."""
    # 1. Escape bare & that are not already part of an entity
    svg = _AMP_RE.sub("&amp;", svg)
    # 2. Replace smart-quotes and dash variants that break XML parsers
    svg = svg.replace("\u2018", "'").replace("\u2019", "'")
    svg = svg.replace("\u201c", '"').replace("\u201d", '"')
    svg = svg.replace("\u2013", "-").replace("\u2014", "-")
    return svg


def _is_valid_xml(svg: str) -> bool:
    try:
        ET.fromstring(svg)
        return True
    except ET.ParseError:
        return False


def _parse_svg(svg: str) -> tuple[bool, str]:
    """Return (is_valid, possibly_repaired_svg).
    Tries raw first, then auto-repairs common LLM mistakes.
    """
    if _is_valid_xml(svg):
        return True, svg
    repaired = _repair_svg_xml(svg)
    if _is_valid_xml(repaired):
        return True, repaired
    return False, svg


def build_llm_svg(
    diagram_type: str,
    data: dict,
    topic: str = "",
    speech: str = "",
    visual_description: str = "",
    max_retries: int = MAX_RETRIES,
) -> str:
    """
    Ask the LLM to generate raw SVG for a diagram.

    Parameters
    ----------
    diagram_type        : str   e.g. "labeled_diagram", "anatomy", "flow"
    data                : dict  enriched diagram data from the lesson plan
    topic               : str   step title — tells the LLM what to draw
    speech              : str   frame speech text — gives anatomical context
    visual_description  : str   LLM-authored layout plan (positions, arrows, labels)
    max_retries         : int   how many LLM attempts before giving up

    Returns
    -------
    Full HTML string on success, empty string on all-retries failure.
    """
    # Import here to avoid circular imports at module load time
    from app.services.llm_service import _call_litellm_proxy  # noqa: PLC0415
    from app.core.config import settings  # noqa: PLC0415

    vis_desc = (visual_description or "").strip()
    visual_layout_line = (
        f"  Visual layout: {vis_desc[:400]}\n"
        "  Follow this layout exactly — positions, arrows, and labels as described above.\n"
        if vis_desc else ""
    )
    if vis_desc:
        logger.debug("build_llm_svg: using visual_description (%d chars) for topic '%s'", len(vis_desc), topic)

    user_prompt = _USER_PROMPT_TEMPLATE.format(
        diagram_type=diagram_type,
        topic=(topic or diagram_type)[:120],
        speech=(speech or "")[:300],
        data_json=json.dumps(data, ensure_ascii=False)[:400] if data else "none",
        visual_layout_line=visual_layout_line,
    )

    # Use the "cheaper" tier (gemini-2.5-flash) for SVG — flash-lite struggles
    # to follow complex multi-rule system prompts reliably.
    # Also override max_tokens: SVG diagrams need 1500-2500 tokens; the default
    # "faster" tier cap of 800 truncates them mid-element (invalid XML).
    from app.core.config import ModelConfig  # noqa: PLC0415
    model_config = settings.get_model_config("cheaper")
    # Clone with increased token budget for SVG generation
    model_config = ModelConfig(
        provider=model_config.provider,
        model_id=model_config.model_id,
        temperature=0.4,
        max_tokens=4096,  # phase-1-only SVG fits in ~800-1500 tokens; 4096 is safe headroom
    )

    for attempt in range(max_retries):
        try:
            result = _call_litellm_proxy(
                prompt=user_prompt,
                model_config=model_config,
                images=[],
                system_prompt=_SYSTEM_PROMPT,
            )
            raw = result.get("text", "")
            if not raw:
                logger.warning("svg_llm: attempt %d/%d — empty LLM response", attempt + 1, max_retries)
                continue

            svg = _extract_svg(raw)
            if not svg:
                logger.warning(
                    "svg_llm: attempt %d/%d — no <svg> found in output (len=%d)",
                    attempt + 1, max_retries, len(raw),
                )
                continue

            # Strip box-drawing/unicode separators the LLM sometimes adds
            svg = re.sub(r"[─-╿═-╬]+", "", svg)

            if "<script" in svg.lower():
                logger.warning("svg_llm: attempt %d/%d — rejected: contains <script>", attempt + 1, max_retries)
                continue

            valid, svg = _parse_svg(svg)
            if not valid:
                logger.warning(
                    "svg_llm: attempt %d/%d — SVG is not well-formed XML (even after repair)",
                    attempt + 1, max_retries,
                )
                continue

            html = _wrap_svg(svg)
            logger.info(
                "svg_llm: ✓ '%s' (%s) on attempt %d | svg=%d chars",
                topic, diagram_type, attempt + 1, len(svg),
            )
            return html

        except Exception as e:
            logger.warning("svg_llm: attempt %d/%d exception: %s", attempt + 1, max_retries, e)

    logger.error(
        "svg_llm: all %d attempts failed for type='%s' topic='%s'",
        max_retries, diagram_type, topic,
    )
    return ""
