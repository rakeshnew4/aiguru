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
You are an expert SVG diagram artist for an educational science and math app.
Draw accurate, richly detailed, TWO-PHASE animated educational diagrams as raw SVG.

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

═══ TWO-PHASE ANIMATION — REQUIRED ═════════════════════════════════════════════
Every diagram MUST have both phases:

PHASE 1 — Draw-on reveal (0 s → ~2 s, plays once):
  Shapes:  stroke-dashoffset PATHLEN→0, dur="0.6s", fill="freeze", begin staggered
  Text:    opacity 0→1, dur="0.35s", fill="freeze"
  Stagger: each element's begin = previous begin + 0.3s

PHASE 2 — Continuous loop (starts at begin="2.5s", runs FOREVER):
  Use repeatCount="indefinite" on ALL looping animations.
  Pick the most appropriate loop for the content:

  ┌─────────────────────┬───────────────────────────────────────────────────┐
  │ Subject             │ Continuous animation                              │
  ├─────────────────────┼───────────────────────────────────────────────────┤
  │ Heart / pump        │ Scale pulse on heart body: 1→1.06→1, dur="0.85s" │
  │                     │ Opacity pulse on blood arrows: 0.4→1→0.4          │
  │ Cell / nucleus      │ Slow rotation of organelle group: 0°→360°, 12s   │
  │                     │ Nucleus radius pulse: r→r+3→r, dur="3s"          │
  │ Blood / flow path   │ animateMotion along vessel path, dur="3s"        │
  │ Photosynthesis      │ Opacity pulse on arrows: 0.3→1→0.3, dur="2s"    │
  │ Electrical circuit  │ Moving dots along wire paths via animateMotion   │
  │ Wave / sound        │ animateTransform translate X: 0→-40→0, dur="2s"  │
  │ Cycle / loop        │ animateTransform rotate around center, dur="8s"  │
  │ DNA / helix         │ animateTransform rotate around center, dur="10s" │
  │ Eye / lens          │ Opacity pulse on light ray: 0.2→0.9→0.2, dur="2s"│
  │ Solar system        │ animateTransform rotate planet groups             │
  │ Labeled diagram     │ Gentle opacity pulse on key central element      │
  └─────────────────────┴───────────────────────────────────────────────────┘

Examples:

Scale pulse (heart beat):
  <animateTransform attributeName="transform" type="scale"
    values="1;1.06;1" dur="0.85s" begin="2.5s"
    repeatCount="indefinite" additive="sum"
    calcMode="spline" keySplines="0.4 0 0.6 1;0.4 0 0.6 1"/>

Opacity pulse (blood flow, arrows):
  <animate attributeName="opacity" values="0.4;1;0.4"
    dur="1.6s" begin="2.5s" repeatCount="indefinite"
    calcMode="spline" keySplines="0.4 0 0.6 1;0.4 0 0.6 1"/>

animateMotion (particle along a path):
  <circle r="4" fill="#4FC3F7">
    <animateMotion dur="3s" begin="2.5s" repeatCount="indefinite">
      <mpath xlink:href="#vessel-path"/>
    </animateMotion>
  </circle>

Slow rotation (cell organelles):
  <animateTransform attributeName="transform" type="rotate"
    from="0 200 150" to="360 200 150"
    dur="12s" begin="2.5s" repeatCount="indefinite"/>

Wave translate (sine/sound):
  <animateTransform attributeName="transform" type="translate"
    values="0,0;-40,0;0,0" dur="2s" begin="2.5s"
    repeatCount="indefinite"/>

═══ DIAGRAM QUALITY RULES ══════════════════════════════════════════════════════
• Draw the REAL anatomical / physical structure — NOT just a circle with labels.
  - Heart     → 4 chambers with curved paths, aorta, pulmonary artery
  - Cell      → membrane, nucleus, mitochondria, ER as distinct shapes
  - Eye       → cornea arc, lens ellipse, retina curve, optic nerve
  - Circuit   → resistor zigzag, capacitor plates, battery symbol, wire lines
  - Apparatus → actual glassware silhouettes (flask, condenser, beaker)
• Use <path d="M … C … Z"> for curved/organic shapes.
• Minimum: main structure + 4 labeled parts + title + Phase 2 loop.
• Fill shapes: fill-opacity="0.25" on main structures for dark-bg visibility.
• Stroke-width 2–3 main structures, 1.5 secondary.
• Define reusable paths with <defs><path id="…"/></defs> for animateMotion.

═══ CONSTRUCTION-FIRST APPROACH — NEVER FLOWCHARTS ═══════════════════════════
CRITICAL: NEVER draw boxes/rectangles as flowchart nodes connected by arrows.
Instead, construct the ACTUAL visual structure of the concept using real shapes.

FORBIDDEN patterns (NEVER draw these):
  ❌ Flow diagram: [Box A] ──→ [Box B] ──→ [Box C] (flowchart with text labels)
  ❌ Abstract boxes with text inside as diagram nodes
  ❌ Simple arrow connections between rectangular nodes

CONSTRUCTION approach per subject (DRAW REAL THINGS):
  Biology/Anatomy → Organ/cell shapes using <ellipse> + <path> curves + labels
    Photosynthesis: Draw a leaf cross-section with chloroplast, sunlight rays, CO₂/O₂
    Digestive: Draw organ shapes (stomach ellipse, intestines as curves), NOT boxes
    Food chain: Draw actual animal silhouettes connected by paths/arrows
  Physics → Apparatus using <path> zigzags, arcs, actual device shapes
    Circuit: Draw resistor zigzags, capacitor parallel lines, wire paths (NOT boxes)
    Lens: Draw actual lens shape (convex/concave arcs), light rays as paths
  Chemistry → Atomic shells using <circle> groups, bond lines using <line>
  Math → Geometric constructions: triangles by <polygon>, circles by <circle>
  Geography → Topographic: mountain peaks as <polygon>, rivers as <path> curves

Use <path d="M … C … L … Z"> to draw organic shapes:
  ✓ Cell membrane as curved path
  ✓ Leaf outline with internal chloroplast structure
  ✓ River/water path with curves
  ✓ Animal body outline for food chain diagrams
  ✓ Organ contours (heart curves, brain lobes, intestine coils)

ALLOWED uses of <rect> ONLY for:
  • Background fill (grid, board background)
  • Equation boxes or text containers (NOT as flowchart nodes)
  • Bar chart bars
  • Structural frames (NOT as process nodes)

For step-by-step processes:
  Instead of boxes→arrows: show the ACTUAL MECHANISM or STAGES
  Water cycle: draw cloud shape → rain → ground/water → evaporation arrows (NO BOXES)
  Mitosis: draw actual cell divisions using circles splitting (NO BOXES)
  Photosynthesis: draw leaf anatomy with arrows showing electron/energy flow (NO BOXES)
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
