"""
diagram_service.py — AI Visual Learning Engine

Pipeline:
  1. Auto-detect diagram type from keywords (free — no LLM)
  2. If confident: generate default data directly (no LLM)
  3. Otherwise: call LLM for diagram_type + data + explanation
  4. Validate & sanitise output
  5. build_from_diagram_type → shapes list
  6. build_animated_svg → HTML

Strict output contract (matches LLM system prompt):
{
  "diagram_type": "flow|cycle|comparison|triangle|circle_radius|rectangle_area|line_graph",
  "data": {},
  "explanation": "max 2–3 lines",
  "visual_intent": "what the student should understand"
}
"""

import json
import logging
import re
from typing import Optional

from app.services.llm_service import generate_response
from app.utils.svg_builder import build_from_diagram_type, build_animated_svg

logger = logging.getLogger(__name__)

# ── System prompt ─────────────────────────────────────────────────────────────

_SYSTEM_PROMPT = """\
You are an AI Visual Learning Assistant for students.

Your goal: Convert a student's question into a simple explanation + a structured diagram.

You MUST:
- Choose the most suitable diagram type
- Keep diagrams simple and clear (max 5–6 elements)
- Use short labels (1–3 words)
- Focus on understanding, not completeness

Available diagram types:
- flow         → process / steps (e.g. "How does digestion work?")
- cycle        → continuous loops (e.g. "water cycle", "carbon cycle", "life cycle")
- comparison   → differences between two things (e.g. "difference between X and Y")
- line_graph   → trends / graphs (e.g. "how population grows", "speed vs time")
- triangle     → geometry with three vertices
                  • set show_incircle=true for "incircle / inscribed circle / inradius"
                  • set show_circumcircle=true for "circumcircle / circumscribed"
                  • set show_height=true for "altitude / height of triangle"
                  • set show_median=true for "median"
- circle_radius → circle with radius labelled
- rectangle_area → area of a rectangle

Decision rules:
- steps / process / how / working / explain → "flow"
- cycle / life cycle / water cycle / carbon cycle / stages of → "cycle"
- difference / vs / compare / contrast → "comparison"
- graph / plot / increase / decrease / trend → "line_graph"
- triangle / angles → "triangle"
- radius / diameter / circle → "circle_radius"
- area / rectangle / perimeter → "rectangle_area"
- anything else → "flow"

Data schemas by type:
flow:           { "steps": ["Step 1", "Step 2", ...] }           ← max 5
cycle:          { "steps": ["Stage 1", "Stage 2", ...] }         ← max 6, ideal 3–5
comparison:     { "left": "A", "right": "B",
                  "left_points": ["point 1", ...],
                  "right_points": ["point 1", ...] }              ← max 4 points each
triangle:       { "labels": ["A", "B", "C"],
                  "show_height": false, "show_incircle": false,
                  "show_circumcircle": false, "show_median": false }
circle_radius:  { "radius": 60, "label": "r" }
rectangle_area: { "width": 120, "height": 70 }
line_graph:     { "points": [[0,0],[1,2],[2,4]], "x_label": "x", "y_label": "y" }

NEVER:
- Include raw coordinates
- Use more than 6 elements
- Write long text inside shapes

Output ONLY valid JSON (no extra text, no markdown, no code fences):
{
  "diagram_type": "...",
  "data": {...},
  "explanation": "2–3 line plain-text summary",
  "visual_intent": "One sentence: what should the student understand from this diagram"
}"""

# ── Keyword auto-detection ────────────────────────────────────────────────────

# Ordered from most-specific to least-specific to avoid mis-classification
_KEYWORD_RULES: list[tuple[str, list[str]]] = [
    ("cycle",          ["water cycle", "carbon cycle", "nitrogen cycle", "rock cycle",
                        "life cycle", "oxygen cycle", "krebs cycle", "cell cycle",
                        "menstrual cycle", "stages of"]),
    ("comparison",     ["difference between", "differences between", "compare", "contrast",
                        "vs ", " vs.", " versus ", "pros and cons"]),
    ("line_graph",     ["graph", "plot", "plotted", "trend", "increase over",
                        "decrease over", "speed vs", "time vs", "growth rate"]),
    ("circle_radius",  ["radius", "diameter", "circumference of a circle"]),
    # triangle sub-types must come BEFORE generic "triangle"
    ("triangle",       ["incircle", "inscribed circle", "in-circle",
                        "circumcircle", "circumscribed circle",
                        "median of", "altitude of",
                        "triangle", "equilateral", "isosceles", "scalene",
                        "right angle triangle", "right-angle triangle"]),
    ("rectangle_area", ["area of a rectangle", "area of rectangle", "perimeter of rectangle",
                        "length and breadth"]),
    ("cycle",          ["cycle"]),    # generic "cycle" fallback after specific ones
    ("flow",           ["process", "steps to", "how does", "how do", "explain how",
                        "working of", "mechanism of", "stages of", "phases of",
                        "what is", "what are"]),
]

# Default data templates used when auto-detect fires (no LLM needed)
_DEFAULT_DATA: dict[str, dict] = {
    "flow":           {"steps": ["Step 1", "Step 2", "Step 3"]},
    "cycle":          {"steps": ["Stage 1", "Stage 2", "Stage 3", "Stage 4"]},
    "comparison":     {"left": "A", "right": "B", "left_points": [], "right_points": []},
    "line_graph":     {"points": [[0, 0], [1, 2], [2, 4], [3, 5]], "x_label": "x", "y_label": "y"},
    "triangle":       {"labels": ["A", "B", "C"], "show_height": False},
    "circle_radius":  {"radius": 70, "label": "r"},
    "rectangle_area": {"width": 120, "height": 70},
}

# Known water cycle steps for instant generation
_KNOWN_CYCLES: dict[str, list[str]] = {
    "water cycle":    ["Evaporation", "Condensation", "Precipitation", "Collection"],
    "carbon cycle":   ["Photosynthesis", "Respiration", "Decomposition", "Combustion"],
    "nitrogen cycle": ["Fixation", "Nitrification", "Assimilation", "Denitrification"],
    "rock cycle":     ["Igneous", "Weathering", "Sedimentary", "Metamorphic"],
    "cell cycle":     ["G1 Phase", "S Phase", "G2 Phase", "Mitosis"],
    "krebs cycle":    ["Acetyl-CoA", "Citrate", "Isocitrate", "Oxaloacetate"],
}


def _auto_detect(question: str) -> Optional[str]:
    """Return diagram_type string if question matches a keyword rule, else None."""
    q = question.lower()
    for dtype, keywords in _KEYWORD_RULES:
        for kw in keywords:
            if kw in q:
                return dtype
    return None


def _triangle_flags_from_question(question: str) -> dict:
    """Return extra boolean flags for the triangle renderer based on keywords."""
    q = question.lower()
    return {
        "show_incircle":      any(k in q for k in ["incircle", "inscribed circle", "in-circle", "inradius"]),
        "show_circumcircle":  any(k in q for k in ["circumcircle", "circumscribed", "circumradius"]),
        "show_height":        any(k in q for k in ["altitude", "height of", "perpendicular height"]),
        "show_median":        any(k in q for k in ["median", "midpoint"]),
    }


def _auto_data_for_cycle(question: str) -> dict:
    """For known cycles, return the canonical steps directly."""
    q = question.lower()
    for cycle_name, steps in _KNOWN_CYCLES.items():
        if cycle_name in q:
            return {"steps": steps}
    return _DEFAULT_DATA["cycle"]


def _sanitise_data(dtype: str, data: dict) -> dict:
    """Clip/validate LLM-supplied data to schema limits."""
    if dtype == "flow":
        steps = [str(s)[:40] for s in (data.get("steps") or [])][:5]
        return {"steps": steps or ["Step 1", "Step 2", "Step 3"]}

    if dtype == "cycle":
        steps = [str(s)[:20] for s in (data.get("steps") or [])][:6]
        return {"steps": steps or ["Stage 1", "Stage 2", "Stage 3"]}

    if dtype == "comparison":
        return {
            "left":         str(data.get("left",  "A"))[:24],
            "right":        str(data.get("right", "B"))[:24],
            "left_points":  [str(p)[:30] for p in (data.get("left_points")  or [])][:4],
            "right_points": [str(p)[:30] for p in (data.get("right_points") or [])][:4],
        }

    if dtype == "line_graph":
        raw_pts = data.get("points") or [[0, 0], [1, 2], [2, 4]]
        pts = []
        for p in raw_pts[:8]:
            try:
                pts.append([float(p[0]), float(p[1])])
            except (TypeError, IndexError, ValueError):
                pass
        return {
            "points":  pts or [[0, 0], [1, 2], [2, 4]],
            "x_label": str(data.get("x_label") or "x")[:10],
            "y_label": str(data.get("y_label") or "y")[:10],
        }

    if dtype == "triangle":
        labels = [str(l)[:6] for l in (data.get("labels") or ["A", "B", "C"])][:3]
        while len(labels) < 3:
            labels.append("")
        return {
            "labels":             labels,
            "show_height":        bool(data.get("show_height",       False)),
            "show_incircle":      bool(data.get("show_incircle",     False)),
            "show_circumcircle":  bool(data.get("show_circumcircle", False)),
            "show_median":        bool(data.get("show_median",       False)),
        }

    if dtype == "circle_radius":
        try:
            r = max(20.0, min(110.0, float(data.get("radius", 70))))
        except (TypeError, ValueError):
            r = 70.0
        return {"radius": r, "label": str(data.get("label") or "r")[:4]}

    if dtype == "rectangle_area":
        try:
            w = max(20.0, min(260.0, float(data.get("width",  120))))
            h = max(15.0, min(180.0, float(data.get("height", 70))))
        except (TypeError, ValueError):
            w, h = 120.0, 70.0
        return {"width": w, "height": h}

    return data or {}


# ── LLM call ──────────────────────────────────────────────────────────────────

_VALID_TYPES = frozenset(_DEFAULT_DATA.keys())

_FALLBACK_RESPONSE = {
    "diagram_type":  "flow",
    "data":          {"steps": ["Understand", "Apply", "Remember"]},
    "explanation":   "A simple 3-step learning process.",
    "visual_intent": "Break complex topics into manageable steps.",
}


def _call_llm(question: str) -> dict:
    """
    Ask the LLM to classify + produce diagram data.
    Returns a validated dict or a hardcoded fallback on any failure.
    """
    full_prompt = f"{_SYSTEM_PROMPT}\n\nStudent question: {question[:500]}"
    try:
        result = generate_response(prompt=full_prompt, tier="faster")
        raw    = result.get("text", "")
        # Extract first JSON object in response
        match  = re.search(r'\{.*\}', raw, re.DOTALL)
        if not match:
            raise ValueError("No JSON found in LLM response")
        payload = json.loads(match.group())
    except Exception as e:
        logger.warning("Diagram LLM call failed: %s — using fallback", e)
        return _FALLBACK_RESPONSE.copy()

    dtype = str(payload.get("diagram_type", "flow")).strip().lower()
    if dtype not in _VALID_TYPES:
        dtype = "flow"

    sanitised_data = _sanitise_data(dtype, payload.get("data") or {})
    return {
        "diagram_type":  dtype,
        "data":          sanitised_data,
        "explanation":   str(payload.get("explanation", ""))[:300],
        "visual_intent": str(payload.get("visual_intent", ""))[:200],
    }


# ── Public API ────────────────────────────────────────────────────────────────

def generate_diagram(question: str) -> dict:
    """
    Full pipeline: auto-detect → (LLM if needed) → validate → render SVG.

    Returns:
        {
          "diagram_type":  str,
          "explanation":   str,
          "visual_intent": str,
          "diagram_html":  str,   ← full animated HTML, empty on render failure
          "source":        "auto" | "llm"
        }
    """
    question = question.strip()
    if not question:
        return {"diagram_html": "", "explanation": "", "diagram_type": "flow",
                "visual_intent": "", "source": "auto"}

    # ── Step 1: try keyword auto-detection ───────────────────────────────────
    detected_type = _auto_detect(question)
    source = "auto"

    if detected_type:
        # Build data without an LLM call for known patterns
        if detected_type == "cycle":
            data = _auto_data_for_cycle(question)
        elif detected_type == "triangle":
            data = _DEFAULT_DATA["triangle"].copy()
            data.update(_triangle_flags_from_question(question))
        else:
            data = _DEFAULT_DATA.get(detected_type, {}).copy()
        explanation   = ""
        visual_intent = ""

        # For comparison and line_graph, auto-detection can't fill meaningful
        # data without context — fall through to LLM
        if detected_type in ("comparison", "line_graph"):
            detected_type = None  # force LLM

    if not detected_type:
        payload       = _call_llm(question)
        detected_type = payload["diagram_type"]
        data          = payload["data"]
        explanation   = payload["explanation"]
        visual_intent = payload["visual_intent"]
        source        = "llm"

    # ── Step 2: build shapes + render ────────────────────────────────────────
    shapes = build_from_diagram_type(detected_type, data)
    html   = build_animated_svg(shapes) if shapes else ""

    if not html:
        # Fallback to flow if renderer returned nothing
        logger.warning("Renderer produced no output for type=%s, falling back to flow", detected_type)
        detected_type = "flow"
        shapes = build_from_diagram_type("flow", {"steps": [question[:20], "…"]})
        html   = build_animated_svg(shapes) if shapes else ""

    return {
        "diagram_type":  detected_type,
        "explanation":   explanation,
        "visual_intent": visual_intent,
        "diagram_html":  html,
        "source":        source,
    }
