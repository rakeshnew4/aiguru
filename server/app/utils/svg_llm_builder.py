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
    return _HTML_PREFIX + svg + _HTML_SUFFIX

# ── System prompt — static, cache-eligible (large, sent as role=system) ──────
_SYSTEM_PROMPT = """\
You are an expert SVG diagram artist for an educational science and math app.
Your job: draw an accurate, richly detailed, animated educational diagram as raw SVG.

═══ HARD RULES ═════════════════════════════════════════════════════════════════
1. Output ONLY the <svg> element.  No markdown, no ```, no explanation.
2. First child of <svg> MUST be: <rect width="400" height="300" fill="#1A2B1A"/>
3. Attribute: viewBox="0 0 400 300" width="100%" xmlns="http://www.w3.org/2000/svg"
4. Keep ALL content inside x=8..392, y=8..292.  Nothing outside this area.
5. Labels ≤ 22 chars each.  Spread them so they do NOT overlap.

═══ COLOR PALETTE ══════════════════════════════════════════════════════════════
Background : #1A2B1A   (dark green — already covered by rule 2)
Blue light : #4FC3F7   Red/pink   : #FF6B6B   Green      : #81C784
Orange     : #FFB74D   Yellow     : #F5E3A0   Cream white: #F0EDD0
Dim grey   : #8BAB8B   Gold glow  : #FFD700   Purple     : #CE93D8

═══ TEXT STYLE ═════════════════════════════════════════════════════════════════
font-family="serif"  fill="#F0EDD0"  (use #F5E3A0 for sub-labels)
Titles: font-size="13" font-weight="bold"
Labels: font-size="11"
Center alignment: text-anchor="middle"

═══ SMIL ANIMATION STYLE ═══════════════════════════════════════════════════════
Shapes draw on with stroke-dasharray / stroke-dashoffset:
    <path stroke-dasharray="PATHLEN" stroke-dashoffset="PATHLEN">
      <animate attributeName="stroke-dashoffset" from="PATHLEN" to="0"
               dur="0.55s" begin="0.0s" fill="freeze"/>
    </path>

Labels / text fade in with opacity:
    <text opacity="0">
      <animate attributeName="opacity" from="0" to="1"
               dur="0.35s" begin="0.9s" fill="freeze"/>
    </text>

Stagger begin= delays: first element at 0.0s, each next +0.3s to +0.5s.
Circles draw on: use stroke-dasharray=CIRCUMFERENCE (2*pi*r).

═══ DIAGRAM QUALITY RULES ══════════════════════════════════════════════════════
• Draw the REAL anatomical / physical structure — NOT just a circle with labels.
  - Heart     → draw actual chambers with curved paths, valves, vessels
  - Cell      → draw cell membrane, nucleus, organelles as shapes
  - Eye       → draw cornea, lens, retina cross-section
  - Circuit   → draw actual component symbols (resistor zigzag, capacitor plates)
  - Apparatus → draw real lab glassware shapes
• Use <path d="M ... C ... Z"> for curved/organic shapes.
• Minimum content per diagram: main structure + 4 labeled parts + title.
• Fill shapes with low-opacity fill (fill-opacity="0.25") for visibility on dark bg.
• Stroke-width 2–3 for main structures, 1.5 for secondary lines.
"""

_USER_PROMPT_TEMPLATE = """\
Draw this educational diagram as SVG:

  Diagram type : {diagram_type}
  Topic/title  : {topic}
  Context      : {speech}
  Data hints   : {data_json}

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
    max_retries: int = MAX_RETRIES,
) -> str:
    """
    Ask the LLM to generate raw SVG for a diagram.

    Parameters
    ----------
    diagram_type : str   e.g. "labeled_diagram", "anatomy", "flow"
    data         : dict  enriched diagram data from the lesson plan
    topic        : str   step title — tells the LLM what to draw
    speech       : str   frame speech text — gives anatomical context
    max_retries  : int   how many LLM attempts before giving up

    Returns
    -------
    Full HTML string on success, empty string on all-retries failure.
    """
    # Import here to avoid circular imports at module load time
    from app.services.llm_service import _call_litellm_proxy  # noqa: PLC0415
    from app.core.config import settings  # noqa: PLC0415

    user_prompt = _USER_PROMPT_TEMPLATE.format(
        diagram_type=diagram_type,
        topic=(topic or diagram_type)[:120],
        speech=(speech or "")[:300],
        data_json=json.dumps(data, ensure_ascii=False)[:400] if data else "none",
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
        temperature=0.4,   # slightly lower temp = fewer hallucinated bad XML chars
        max_tokens=20480,
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
