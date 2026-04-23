"""
diagram_router.py — Teacher Engine Classifier & Decision Layer

Pipeline:
  User question
      ↓
  classify_diagram_need(question, subject_hint)
      ↓
  DiagramDecision(needed=True/False, diagram_type, data_hints)
      ↓
  (if needed) LLM generates diagram_type + data JSON
      ↓
  build_from_diagram_type(diagram_type, data)  →  Animated SVG

Key principle:
  LLM outputs *semantic meaning*, not coordinates.
  Python renderer handles all math, geometry, and animation.

Subject → SVG rules (hard-coded, never let LLM override):
  - language / stories / social / history  →  NEVER SVG
  - math geometry / graphs                →  ALWAYS SVG
  - science processes / structures        →  SVG when confidence ≥ 0.6
  - definitions / arithmetic / algebra    →  NO SVG (text is enough)
"""

from __future__ import annotations
import re
from dataclasses import dataclass, field
from typing import Optional

from app.core.logger import get_logger

logger = get_logger(__name__)


# ── Subject categories ────────────────────────────────────────────────────────

_NEVER_SVG_SUBJECTS = frozenset({
    "language", "english", "hindi", "urdu", "bengali", "literature",
    "grammar", "essay", "story", "poem", "history", "civics",
    "social", "social_science", "geography", "economics", "political_science",
})

_ALWAYS_SVG_TOPICS = frozenset({
    # Math geometry
    "triangle", "rectangle", "circle", "polygon", "angle", "quadrilateral",
    "parallelogram", "rhombus", "trapezium", "congruent", "similar",
    "pythagoras", "area", "perimeter", "volume",
    # Math graphs
    "graph", "function", "linear", "parabola", "hyperbola", "slope", "intercept",
    "coordinate", "axis", "plot", "curve",
    # Science structures
    "atom", "electron", "nucleus", "orbit", "orbital", "molecule", "bond",
    "cell", "mitosis", "meiosis", "dna", "chromosome", "photosynthesis",
    "solar system", "planet", "orbit", "force", "circuit", "electromagnetic",
    "wave", "refraction", "reflection", "lens", "prism",
    # Physics
    "projectile", "velocity", "acceleration", "free body", "free-body",
    "tension", "friction",
})

_DIAGRAM_KEYWORD_MAP: dict[str, str] = {
    # Science — longer keys have higher priority (score = len(key))
    "atom": "atom",
    "bohr": "atom",
    "electron": "atom",
    "nucleus": "atom",
    "solar system": "solar_system",
    "planet": "solar_system",
    "solar": "solar_system",
    "revolution": "solar_system",
    "cell": "labeled_diagram",
    "mitosis": "cycle",
    "meiosis": "cycle",
    "dna": "labeled_diagram",
    "chromosome": "labeled_diagram",
    "photosynthesis": "leaf_anatomy",
    "food chain": "food_chain_anatomy",
    "water cycle": "cycle",
    "carbon cycle": "cycle",
    "nitrogen cycle": "cycle",
    "digestive": "digestive_system",
    "circulatory": "blood_circulation",
    "circuit": "labeled_diagram",
    "refraction": "labeled_diagram",
    "reflection": "labeled_diagram",
    "wave": "waveform_signal",
    "sound wave": "waveform_signal",
    "light wave": "waveform_signal",
    "electromagnetic": "waveform_signal",
    # Math — geometry
    "triangle": "triangle",
    "right triangle": "triangle",
    "isosceles triangle": "triangle",
    "equilateral triangle": "triangle",
    "scalene triangle": "triangle",
    "obtuse triangle": "triangle",
    "pythagoras": "pythagoras",
    "pythagorean theorem": "pythagoras",
    "pythagorean": "pythagoras",
    "hypotenuse": "pythagoras",
    "right angle theorem": "pythagoras",
    "polygon": "polygon",
    "hexagon": "polygon",
    "pentagon": "polygon",
    "octagon": "polygon",
    "regular polygon": "polygon",
    "rectangle": "rectangle_area",
    "area": "rectangle_area",
    "perimeter": "rectangle_area",
    "circle": "circle_geometry",
    "radius": "circle_geometry",
    "diameter": "circle_geometry",
    "circumference": "circle_geometry",
    "chord": "circle_geometry",
    "sector": "circle_geometry",
    "tangent to circle": "circle_geometry",
    "arc of circle": "circle_geometry",
    "angle": "angle",
    "acute angle": "angle",
    "obtuse angle": "angle",
    "right angle": "angle",
    "reflex angle": "angle",
    "supplementary": "angle",
    "complementary angle": "angle",
    "vertical angles": "angle",
    "venn diagram": "venn_diagram",
    "set theory": "venn_diagram",
    "intersection of sets": "venn_diagram",
    "union of sets": "venn_diagram",
    "coordinate plane": "coordinate_plane",
    "cartesian plane": "coordinate_plane",
    "x-y plane": "coordinate_plane",
    "plot points": "coordinate_plane",
    "bar chart": "bar_chart",
    "bar graph": "bar_chart",
    "histogram": "bar_chart",
    "pie chart": "pie_chart",
    "pie graph": "pie_chart",
    # Math — functions (longer phrases → higher priority score)
    "quadratic equation": "graph_function",
    "quadratic": "graph_function",
    "parabola": "graph_function",
    "x squared": "graph_function",
    "x^2": "graph_function",
    "polynomial": "graph_function",
    "cubic function": "graph_function",
    "cubic": "graph_function",
    "linear function": "graph_function",
    "graph a function": "graph_function",
    "plot the function": "graph_function",
    "plot the graph": "graph_function",
    "function graph": "graph_function",
    "sine curve": "graph_function",
    "cosine curve": "graph_function",
    "trig function": "graph_function",
    # Math — simpler graph (shorter keywords → lower score → only win if no function match)
    "graph": "line_graph",
    "linear equation": "line_graph",
    "slope": "line_graph",
    "intercept": "line_graph",
    "scatter": "line_graph",
    "coordinate": "coordinate_plane",
    "axis": "line_graph",
    "plot": "line_graph",
    "curve": "line_graph",
    # Number / fraction
    "number line": "number_line",
    "comparing fractions": "fraction_bar",
    "fractions": "fraction_bar",
    "fraction": "fraction_bar",
    # Comparison — long phrases beat short ones
    "compare and contrast": "comparison",
    "comparison between": "comparison",
    "differences between": "comparison",
    "difference between": "comparison",
    "compare mitosis": "comparison",
    "compare meiosis": "comparison",
    "mitosis vs meiosis": "comparison",
    "versus": "comparison",
    "vs.": "comparison",
    " vs ": "comparison",
    "compare": "comparison",
    # Flow / cycle — only specific named processes, NOT generic words like "steps" or "process"
    "photosynthesis steps": "leaf_anatomy_steps",
    "life cycle": "cycle",
    "stages of": "cycle",
    "phases of": "cycle",
    "cycle": "cycle",

    # ── CBSE Physics (Grades 6–10) ────────────────────────────────────────────
    "newton first law":       "labeled_diagram",
    "newton second law":      "labeled_diagram",
    "newton third law":       "labeled_diagram",
    "newton laws of motion":  "labeled_diagram",
    "laws of motion":         "labeled_diagram",
    "uniform motion":         "line_graph",
    "non-uniform motion":     "line_graph",
    "distance time graph":    "line_graph",
    "speed time graph":       "line_graph",
    "velocity time graph":    "line_graph",
    "friction force":         "comparison",
    "types of friction":      "comparison",
    "gravitational force":    "labeled_diagram",
    "free fall":              "labeled_diagram",
    "projectile motion":      "labeled_diagram",
    "pressure in fluids":     "labeled_diagram",
    "archimedes principle":   "labeled_diagram",
    "buoyancy":               "labeled_diagram",
    "work done formula":      "labeled_diagram",
    "kinetic energy":         "graph_function",
    "potential energy":       "graph_function",
    "power formula":          "labeled_diagram",
    "sound waves":            "waveform_signal",
    "transverse wave":        "waveform_signal",
    "longitudinal wave":      "waveform_signal",
    "amplitude frequency":    "waveform_signal",
    "reflection of light":    "labeled_diagram",
    "refraction of light":    "labeled_diagram",
    "convex lens":            "labeled_diagram",
    "concave lens":           "labeled_diagram",
    "electric circuit":       "labeled_diagram",
    "series circuit":         "labeled_diagram",
    "parallel circuit":       "comparison",
    "ohm law":                "labeled_diagram",
    "magnetic field lines":   "labeled_diagram",
    "electromagnet":          "labeled_diagram",

    # ── CBSE Chemistry (Grades 6–10) ─────────────────────────────────────────
    "atomic structure":       "atom",
    "electron configuration": "atom",
    "bohr model":             "atom",
    "ionic bond":             "comparison",
    "covalent bond":          "labeled_diagram",
    "ionic vs covalent":      "comparison",
    "acids and bases":        "comparison",
    "ph scale":               "number_line",
    "ph of solutions":        "number_line",
    "metals and nonmetals":   "comparison",
    "physical chemical change":"comparison",
    "exothermic endothermic": "comparison",
    "periodic trends":        "line_graph",
    "valency":                "labeled_diagram",
    "carbon compounds":       "labeled_diagram",

    # ── CBSE Biology (Grades 6–10) ────────────────────────────────────────────
    "animal cell":            "labeled_diagram",
    "plant cell":             "labeled_diagram",
    "cell membrane":          "labeled_diagram",
    "cell organelles":        "labeled_diagram",
    "mitochondria":           "labeled_diagram",
    "chloroplast":            "labeled_diagram",
    "nucleus structure":      "labeled_diagram",
    "digestive system":       "digestive_system_anatomy",
    "nervous system":         "nervous_system_diagram",
    "circulatory system":     "blood_circulation_system",
    "excretory system":       "excretory_system_diagram",
    "respiratory system":     "respiratory_system_diagram",
    "blood circulation":      "blood_circulation_anatomy",
    "photosynthesis process": "leaf_anatomy_process",
    "respiration process":    "respiration_process_diagram",
    "food web":               "food_web_diagram",
    "ecosystem":              "labeled_diagram",
    "pollination":            "pollination_diagram",
    "germination":            "germination_diagram",
    "cell division":          "cycle",

    # ── CBSE Mathematics (Grades 6–10) ───────────────────────────────────────
    "integers on number line": "number_line",
    "rational numbers":        "number_line",
    "negative numbers":        "number_line",
    "comparing fractions":     "fraction_bar",
    "equivalent fractions":    "fraction_bar",
    "linear equations":        "graph_function",
    "quadratic formula":       "graph_function",
    "standard form":           "graph_function",
    "vertex of parabola":      "graph_function",
    "arithmetic progression":  "line_graph",
    "geometric progression":   "line_graph",
    "mean median mode":        "bar_chart",
    "frequency distribution":  "bar_chart",
    "probability":             "fraction_bar",
    "pie chart data":          "pie_chart",
    "bar chart data":          "bar_chart",
    "complementary angles":    "geometry_angles",
    "supplementary angles":    "geometry_angles",
    "vertically opposite":     "geometry_angles",
    "exterior angle":          "geometry_angles",
    "interior angle":          "geometry_angles",
    "congruent triangles":     "triangle",
    "similar triangles":       "triangle",
    "mid point theorem":       "triangle",
    "basic proportionality":   "triangle",
    "tangent to a circle":     "circle_geometry",
    "chord of circle":         "circle_geometry",
    "arc of circle":           "circle_geometry",
    "sector of circle":        "circle_geometry",
    "sets venn":               "venn_diagram",
}


# ── Decision result ───────────────────────────────────────────────────────────

@dataclass
class DiagramDecision:
    needed: bool
    diagram_type: str = ""          # e.g. "atom", "triangle", "flow"
    confidence: float = 0.0         # 0.0–1.0
    subject: str = ""               # detected subject bucket
    reason: str = ""                # human-readable why/why-not


# ── Classifier ────────────────────────────────────────────────────────────────

def classify_diagram_need(
    question: str,
    subject_hint: str = "",         # from BB planner topic_type field
    topic_keywords: list[str] | None = None,  # BB planner key_concepts
) -> DiagramDecision:
    """
    Rule-based classifier.  Fast, deterministic, zero LLM cost.
    Returns DiagramDecision with needed=True/False and best diagram_type.

    subject_hint maps to BB planner topic_type:
        math_formula | science_process | definition | comparison | history | other
    """
    q = question.lower()
    subject = (subject_hint or "").lower()

    # ── Hard block: subjects that NEVER use SVG ───────────────────────────────
    for blocked in _NEVER_SVG_SUBJECTS:
        if blocked in subject or blocked in q:
            return DiagramDecision(
                needed=False, subject=blocked,
                reason=f"subject '{blocked}' never uses SVG",
            )

    # BB planner topic_type → hard skip for non-visual types
    if subject in ("history", "definition"):
        return DiagramDecision(
            needed=False, subject=subject,
            reason=f"topic_type '{subject}' is text-only",
        )

    # ── Keyword scan: find best matching diagram type ─────────────────────────
    best_type = ""
    best_score = 0

    for kw, dtype in _DIAGRAM_KEYWORD_MAP.items():
        if kw in q:
            score = len(kw)          # longer keyword = more specific = higher priority
            if score > best_score:
                best_score, best_type = score, dtype

    # Also check BB planner concepts
    for concept in (topic_keywords or []):
        c = concept.lower()
        for kw, dtype in _DIAGRAM_KEYWORD_MAP.items():
            if kw in c:
                score = len(kw) + 2  # planner concepts get a bonus
                if score > best_score:
                    best_score, best_type = score, dtype

    # ── Subject-level heuristics when keyword scan found nothing ──────────────
    # NOTE: We intentionally do NOT fall back to "flow" for science_process —
    # too many concept questions get misclassified as flowcharts.
    if not best_type:
        if subject == "comparison":
            best_type = "comparison"
            best_score = 3   # low confidence

    if not best_type:
        return DiagramDecision(
            needed=False, subject=subject,
            reason="no diagram keyword or subject match found",
        )

    # ── Confidence scoring ────────────────────────────────────────────────────
    confidence = min(0.5 + best_score * 0.04, 0.97)

    # Boost for explicit visual-instruction words
    if any(w in q for w in ("draw", "show", "diagram", "sketch", "illustrate", "visualise", "visualize", "explain with")):
        confidence = min(confidence + 0.15, 0.98)

    # Penalise pure definition questions
    if re.search(r"^(what is|define|meaning of|what does)\b", q):
        confidence = max(confidence - 0.20, 0.10)

    needed = confidence >= 0.35

    logger.debug(
        "diagram_router: q='%s' type=%s conf=%.2f needed=%s",
        question[:60], best_type, confidence, needed,
    )
    return DiagramDecision(
        needed=needed,
        diagram_type=best_type,
        confidence=confidence,
        subject=subject,
        reason=f"keyword match '{best_type}' conf={confidence:.2f}",
    )


# ── Diagram type → LLM prompt fragment ───────────────────────────────────────
#
# When the BB prompt asks the LLM to produce a diagram frame, it should output:
#   {"frame_type": "diagram", "diagram_type": "<type>", "data": {...}}
# NOT raw svg_elements.  The data dict uses this schema per type.

DIAGRAM_DATA_SCHEMAS: dict[str, str] = {
    "atom": (
        'data: {"nucleus_label":"<1-3 char symbol>","nucleus_color":"highlight",'
        '"orbits":[{"electrons":N,"color":"<key>"}],"duration":12}'
    ),
    "solar_system": (
        'data: {"sun_label":"Sun","planets":[{"label":"<name>","color":"<key>","duration":N}]}'
    ),
    "triangle": (
        'data: {"labels":["A","B","C"],"show_height":true|false,'
        '"show_incircle":false,"show_circumcircle":false}'
    ),
    "circle_radius": (
        'data: {"radius":70,"label":"r"}'
    ),
    "rectangle_area": (
        'data: {"width":140,"height":80}'
    ),
    "line_graph": (
        'data: {"points":[[0,0],[1,2],[2,4]],"x_label":"x","y_label":"y"}'
    ),
    "flow": (
        'data: {"title":"<short title>","steps":["Step1","Step2","Step3","Step4"]}'
    ),
    "cycle": (
        'data: {"title":"<cycle name>","steps":["Stage1","Stage2","Stage3"]}'
    ),
    "comparison": (
        'data: {"left":"A","right":"B",'
        '"left_points":["point1","point2"],"right_points":["point1","point2"]}'
    ),
    "labeled_diagram": (
        'data: {"center":"<name>","center_shape":"circle",'
        '"parts":["Part1","Part2","Part3","Part4"]}'
    ),
    "waveform_signal": (
        'data: {"title":"<wave name>","wave_type":"sine","cycles":2.5,"amplitude":50,'
        '"x_label":"time","y_label":"amplitude"}'
    ),
    "number_line": (
        'data: {"start":-5,"end":5,"marked_points":[0,2,-3],'
        '"highlight_range":[1,4],"label":"Number Line"}'
    ),
    "fraction_bar": (
        'data: {"fractions":[{"num":1,"den":2},{"num":3,"den":4}],"title":"Comparing Fractions"}'
    ),
    "graph_function": (
        'data: {"function":"quadratic|linear|cubic|sine","a":1,"b":0,"c":0,'
        '"x_range":[-4,4],"label":"y = ax² + bx + c"}'
    ),
}


def get_diagram_prompt_hint(diagram_type: str) -> str:
    """Return the data schema hint string for a given diagram type."""
    return DIAGRAM_DATA_SCHEMAS.get(diagram_type, "")
