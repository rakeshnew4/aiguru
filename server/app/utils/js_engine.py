"""
js_engine.py — Python bridge for the JS rendering engine.

Reads the four engine JS files (core, shapes, motion, diagrams) and inlines
them into a self-contained HTML page that Android WebView can load.

The HTML page:
  1. Creates an SVG with viewBox="0 0 400 300" width="100%" height="auto"
  2. Loads the engine JS (inlined, so no network requests needed)
  3. Calls the appropriate Diagrams.<type>(engine, svg, descriptor) function
  4. Adds pause/replay buttons via createControls()

Usage:
    from app.utils.js_engine import build_js_diagram_html

    html = build_js_diagram_html("atom", {"symbol": "Na", "protons": 11})
    html = build_js_diagram_html("solarSystem", {"planets": [...]})
    html = build_js_diagram_html("wave", {"label": "Sound Wave"})
    html = build_js_diagram_html("sun", {})
    html = build_js_diagram_html("plant", {"leaves": 3})
    html = build_js_diagram_html("flowArrow", {"steps": ["Input","Process","Output"]})
"""

from __future__ import annotations

import json
import logging
import os
import re
from functools import lru_cache
from pathlib import Path

logger = logging.getLogger(__name__)

# Engine JS files in load order (dependencies first)
_ENGINE_DIR = Path(__file__).parent.parent / "static" / "engine"
_JS_FILES   = ["core.js", "shapes.js", "motion.js", "diagrams.js"]

# Diagram types supported by the JS engine
# Maps the descriptor key → JS function name on window.Diagrams
SUPPORTED_TYPES: dict[str, str] = {
    # Science / physics
    "atom":             "atom",
    "solar_system":     "solarSystem",
    "wave":             "wave",
    "waveform":         "wave",
    "waveform_signal":  "wave",
    "sine_wave":        "wave",
    "sun":              "sun",
    "plant":            "plant",
    # Process flows
    "flow_arrow":       "flowArrow",
    "flow":             "flowArrow",
    # Cycle, structure, comparison
    "cycle":            "cycle",
    "labeled_diagram":  "labeled",
    "anatomy":          "labeled",
    "cell":             "labeled",
    "cell_diagram":     "labeled",
    "comparison":       "comparison",
    "custom":           "custom",
    "custom_path":      "custom",
    # ── Biology pre-generators ───────────────────────────────────────────
    "heart":                "heart",
    "heart_basic":          "heart",
    "heart_labeled":        "heart",
    "heart_diagram":        "heart",
    "neuron":               "neuron",
    "nerve_cell":           "neuron",
    "neuron_structure":     "neuron",
    "dna":                  "dna",
    "dna_helix":            "dna",
    "dna_double_helix":     "dna",
    # ── Physics pre-generators ───────────────────────────────────────────
    "pendulum":             "pendulum",
    "simple_pendulum":      "pendulum",
    "spring_mass":          "springMass",
    "spring":               "springMass",
    "spring_oscillation":   "springMass",
    "lens":                 "lens",
    "convex_lens":          "lens",
    "ray_diagram_lens":     "lens",
    "ray_diagram":          "lens",
    "electric_field":       "electricField",
    "electric_field_lines": "electricField",
    "electrostatic_field":  "electricField",
    # ── Mathematics renderers ────────────────────────────────────────────
    "number_line":          "numberLine",
    "fraction_bar":         "fractionBar",
    "fraction":             "fractionBar",
    "graph_function":       "graphFunction",
    "math_graph":           "graphFunction",
    "function_graph":       "graphFunction",
    "parabola":             "graphFunction",
    "triangle":             "triangle",
    "right_triangle":       "triangle",
    "isosceles_triangle":   "triangle",
    "equilateral_triangle": "triangle",
    "scalene_triangle":     "triangle",
    "obtuse_triangle":      "triangle",
    "polygon":              "regularPolygon",
    "regular_polygon":      "regularPolygon",
    "hexagon":              "regularPolygon",
    "pentagon":             "regularPolygon",
    "octagon":              "regularPolygon",
    "circle_geometry":      "circleGeometry",
    "circle_radius":        "circleGeometry",
    "circle_diagram":       "circleGeometry",
    "circle":               "circleGeometry",
    "coordinate_plane":     "coordinatePlane",
    "coordinate_graph":     "coordinatePlane",
    "cartesian_plane":      "coordinatePlane",
    "scatter_plot":         "coordinatePlane",
    "venn_diagram":         "vennDiagram",
    "venn":                 "vennDiagram",
    "bar_chart":            "barChart",
    "bar_graph":            "barChart",
    "histogram":            "barChart",
    "pie_chart":            "pieChart",
    "pie_graph":            "pieChart",
    "angle":                "angleGeometry",
    "geometry_angles":      "geometryAngles",
    "angle_diagram":        "angleGeometry",
    "angles":               "angleGeometry",
    "line_graph":           "lineGraph",
    "line_chart":           "lineGraph",
    "data_graph":           "lineGraph",
    "pythagoras":           "pythagorasTheorem",
    "pythagoras_theorem":   "pythagorasTheorem",
    "pythagorean_theorem":  "pythagorasTheorem",
    "right_angle_theorem":  "pythagorasTheorem",
    # ── New geometry types ───────────────────────────────────────────────
    "right_angle":          "rightAngle",
    "perpendicular":        "rightAngle",
    "90_degree":            "rightAngle",
    "polygon_formation":    "polygonFormation",
    "polygon_family":       "polygonFormation",
    "polygons":             "polygonFormation",
    "polygon_progression":  "polygonFormation",
}


@lru_cache(maxsize=1)
def _load_engine_js() -> str:
    """
    Read and concatenate all engine JS files.
    Cached so the files are read only once per process lifetime.
    Returns empty string if any file is missing (logs a warning).
    """
    parts: list[str] = []
    for fname in _JS_FILES:
        fpath = _ENGINE_DIR / fname
        if not fpath.exists():
            logger.warning("js_engine: missing engine file %s", fpath)
            return ""
        parts.append(fpath.read_text(encoding="utf-8"))
    js = "\n\n".join(parts)

    # ── Strip block comments (/* ... */) ──────────────────────────────────────
    # This removes all JSDoc comments (which contain the literal </script> that
    # would prematurely close the HTML <script> block) and cuts payload ~65%.
    js = re.sub(r'/\*[\s\S]*?\*/', '', js)

    # ── Safety: escape any remaining </script in code/strings ─────────────────
    # Replaces </script with <\/script — invisible to HTML parser, valid in JS.
    js = re.sub(r'<(/script)', r'<\\\1', js, flags=re.IGNORECASE)

    # ── Clean up blank lines left by comment removal ───────────────────────────
    js = re.sub(r'\n{3,}', '\n\n', js)

    return js.strip()


def _invalidate_engine_cache() -> None:
    """Call in development when engine JS changes on disk."""
    _load_engine_js.cache_clear()


def build_js_diagram_html(diagram_type: str, data: dict | None) -> str:
    """
    Build a self-contained HTML page that renders the given diagram type
    using the JS engine.

    Parameters
    ----------
    diagram_type : str
        One of the keys in SUPPORTED_TYPES  (e.g. "atom", "solar_system").
    data : dict | None
        Descriptor data forwarded verbatim to the JS renderer as JSON.

    Returns
    -------
    str
        Full HTML string, or empty string if type is unsupported or engine
        files are missing.
    """
    js_fn = SUPPORTED_TYPES.get((diagram_type or "").strip().lower())
    if not js_fn:
        logger.debug("js_engine: unsupported diagram type '%s'", diagram_type)
        return ""

    engine_js = _load_engine_js()
    if not engine_js:
        logger.warning("js_engine: engine JS unavailable, skipping '%s'", diagram_type)
        return ""

    descriptor_json = json.dumps(data or {}, ensure_ascii=False)

    return _HTML_TEMPLATE.format(
        engine_js=engine_js,
        js_fn=js_fn,
        descriptor=descriptor_json,
    )


# ── HTML template ─────────────────────────────────────────────────────────────
# Engine JS is inlined so the HTML is fully self-contained.
# WebView loads it with loadDataWithBaseURL("file:///android_asset/", ...)
# No asset file required — works in any WebView origin.
# {engine_js}   — stripped+escaped engine JS (~55KB)
# {js_fn}       — Diagrams function name, e.g. "triangle"
# {descriptor}  — JSON descriptor

_HTML_TEMPLATE = """\
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  *{{margin:0;padding:0;box-sizing:border-box}}
  html,body{{
    width:100%;height:100vh;
    background:#1A2B1A;
    display:flex;flex-direction:column;
    align-items:center;justify-content:center;
    overflow:hidden;
  }}
  svg{{width:100%;max-width:100%;flex:1;min-height:0;display:block}}
  #controls{{
    display:flex;gap:8px;justify-content:center;padding:4px 0;flex-shrink:0;
  }}
  button{{
    background:#2a3f2a;border:1px solid #4a6a4a;color:#e0f0e0;
    border-radius:6px;padding:4px 14px;font-size:14px;cursor:pointer;
  }}
</style>
</head>
<body>
<svg id="diagram" viewBox="0 0 400 300" width="100%"
     xmlns="http://www.w3.org/2000/svg">
</svg>
<div id="controls"></div>
<script>
{engine_js}
</script>
<script>
(function() {{
  var svg        = document.getElementById('diagram');
  var descriptor = {descriptor};
  var engine     = new DiagramEngine(svg, descriptor, {{ phaseMs: 2500 }});
  try {{
    Diagrams.{js_fn}(engine, svg, descriptor);
  }} catch(e) {{
    var t = document.createElementNS('http://www.w3.org/2000/svg','text');
    t.setAttribute('x','200'); t.setAttribute('y','150');
    t.setAttribute('text-anchor','middle'); t.setAttribute('fill','#FF6B6B');
    t.setAttribute('font-size','13');
    t.textContent = 'Error: ' + e.message;
    svg.appendChild(t);
    if (typeof console !== 'undefined') console.error(e);
  }}
  createControls(engine, document.getElementById('controls'));
  engine.start();
}})();
</script>
</body>
</html>
"""
