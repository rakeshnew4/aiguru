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
    # NEW: cycle, labeled structure, comparison, custom path
    "cycle":            "cycle",
    "labeled_diagram":  "labeled",
    "anatomy":          "labeled",
    "cell":             "labeled",
    "cell_diagram":     "labeled",
    "comparison":       "comparison",
    "custom":           "custom",
    "custom_path":      "custom",
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
    return "\n\n".join(parts)


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
# {engine_js}   — inlined JS engine (core + shapes + motion + diagrams)
# {js_fn}       — Diagrams function name, e.g. "atom"
# {descriptor}  — JSON descriptor passed to the renderer

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
  svg{{width:100%;max-width:100%;flex:1;min-height:0}}
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
     xmlns="http://www.w3.org/2000/svg" style="display:block">
</svg>
<div id="controls"></div>
<script>
/* ── Engine (inlined) ── */
{engine_js}

/* ── Scene bootstrap ── */
(function() {{
  const svg        = document.getElementById('diagram');
  const descriptor = {descriptor};
  const engine     = new DiagramEngine(svg, descriptor, {{ phaseMs: 2500 }});

  try {{
    Diagrams.{js_fn}(engine, svg, descriptor);
  }} catch(e) {{
    // Fallback: show error text in SVG so it's not blank
    var t = document.createElementNS('http://www.w3.org/2000/svg','text');
    t.setAttribute('x','200'); t.setAttribute('y','150');
    t.setAttribute('text-anchor','middle'); t.setAttribute('fill','#FF6B6B');
    t.setAttribute('font-size','12'); t.textContent = 'Diagram error: ' + e.message;
    svg.appendChild(t);
  }}

  createControls(engine, document.getElementById('controls'));
  engine.start();
}})();
</script>
</body>
</html>
"""
