"""
svg_builder.py — Server-side animated SVG HTML builder (entry point).

Architecture:
  LLM / auto-detect  → diagram_type + data   (structured, no raw coords)
  Python layout engine → shapes list         (deterministic, no overlap)
  build_animated_svg  → full animated HTML   (SMIL, chalk style)
  Android WebView     → renders HTML as-is

Split into sub-modules (each ≤ 500 lines):
  svg_colors.py        — Colour palette, layout utils, HTML escape
  svg_primitives.py    — SMIL shape renderer (_render_shape)
  svg_renderers.py     — Geometry + chart renderers
  svg_renderers_sci.py — Science renderers (atom, solar, wave)
  svg_renderers_math.py— Math renderers (number_line, fraction_bar, etc.)
  svg_atom.py          — JS+SVG atom animation HTML generator

This file exposes the public API:
  build_from_diagram_type(diagram_type, data) → list[shape]
  build_animated_svg(elements)                → HTML string
  build_atom_html(data)                       → HTML string  (legacy path)
"""

import json
from collections import defaultdict

from app.utils.svg_colors import (
    COLORS, STROKE, ACCENT, BG_COLOR,
    _STAGE_STEP, _resolve_color,
)
from app.utils.svg_primitives import _render_shape
from app.utils.svg_renderers import (
    _render_triangle,
    _render_circle_radius,
    _render_rectangle_area,
    _render_line_graph,
    _render_flow,
    _render_comparison,
    _render_cycle,
    _render_labeled_diagram,
)
from app.utils.svg_renderers_sci import (
    _render_atom,
    _render_solar_system,
    _render_waveform_signal,
)
from app.utils.svg_renderers_math import (
    _render_number_line,
    _render_fraction_bar,
    _render_graph_function,
    _render_geometry_angles,
)
from app.utils.svg_atom import build_atom_html  # noqa: F401 — re-exported

# ── Renderer registry ─────────────────────────────────────────────────────────

_RENDERERS = {
    # Geometry
    "triangle":        _render_triangle,
    "circle_radius":   _render_circle_radius,
    "rectangle_area":  _render_rectangle_area,
    "geometry_angles": _render_geometry_angles,
    "angles":          _render_geometry_angles,
    # Graphs / data
    "line_graph":      _render_line_graph,
    "graph_function":  _render_graph_function,
    "function_plot":   _render_graph_function,
    "parabola":        _render_graph_function,
    # Number / fraction
    "number_line":     _render_number_line,
    "fraction_bar":    _render_fraction_bar,
    "fractions":       _render_fraction_bar,
    # Concept / structure
    "flow":            _render_flow,
    "comparison":      _render_comparison,
    "cycle":           _render_cycle,
    "labeled_diagram": _render_labeled_diagram,
    "anatomy":         _render_labeled_diagram,
    "cell":            _render_labeled_diagram,
    "cell_diagram":    _render_labeled_diagram,
    # Science
    "atom":            _render_atom,
    "solar_system":    _render_solar_system,
    "waveform_signal": _render_waveform_signal,
    "sine_wave":       _render_waveform_signal,
    "wave":            _render_waveform_signal,
}


def build_from_diagram_type(diagram_type: str, data: dict) -> list:
    """
    Convert a structured diagram_type + data dict into a list of shape dicts.
    Returns [] if diagram_type is unknown.
    """
    renderer = _RENDERERS.get((diagram_type or "").strip().lower())
    if not renderer:
        return []
    try:
        return renderer(data or {})
    except Exception:
        return []


# ── SVG marker defs ───────────────────────────────────────────────────────────

def _make_defs(has_arrow: bool, has_double_arrow: bool) -> str:
    if not has_arrow and not has_double_arrow:
        return ""
    markers = '<defs>'
    if has_arrow:
        markers += (
            '<marker id="arrow" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">'
            f'<path d="M0,0 L10,3.5 L0,7 Z" fill="{ACCENT}"/>'
            '</marker>'
        )
    if has_double_arrow:
        markers += (
            '<marker id="arrow_back" markerWidth="10" markerHeight="7" refX="1" refY="3.5" orient="auto-start-reverse">'
            f'<path d="M0,0 L10,3.5 L0,7 Z" fill="{ACCENT}"/>'
            '</marker>'
        )
    markers += '</defs>'
    return markers


# ── Main HTML builder ─────────────────────────────────────────────────────────

def build_animated_svg(elements) -> str:
    """
    Convert a list of shape dicts (or JSON string) into a full animated HTML page.

    Animation storytelling:
      If shapes carry an "animation_stage" key (int ≥ 0), shapes at the same stage
      animate together — creating a step-by-step teaching sequence.
      Shapes without "animation_stage" animate sequentially as before (legacy).

    Returns empty string on any error or empty input.
    """
    if isinstance(elements, str):
        try:
            elements = json.loads(elements)
        except (json.JSONDecodeError, TypeError):
            return ""

    if not elements or not isinstance(elements, list):
        return ""

    # Separate staged vs legacy shapes
    has_stages = any(isinstance(el, dict) and "animation_stage" in el for el in elements)

    parts            = []
    has_arrow        = False
    has_double_arrow = False

    if has_stages:
        stage_map: dict[int, list] = defaultdict(list)
        for el in elements:
            if not isinstance(el, dict):
                continue
            stage = int(el.get("animation_stage", 0))
            stage_map[stage].append(el)

        delay = 0.0
        for stage in sorted(stage_map.keys()):
            stage_delay     = delay
            stage_max_added = 0.0
            for el in stage_map[stage]:
                if el.get("shape") in ("arrow", "curved_arrow", "double_arrow", "elbow_arrow"):
                    has_arrow = True
                if el.get("shape") == "double_arrow":
                    has_double_arrow = True
                svg_str, new_delay = _render_shape({**el}, stage_delay)
                if svg_str:
                    parts.append(svg_str)
                    added = new_delay - stage_delay
                    if added > stage_max_added:
                        stage_max_added = added
            delay += stage_max_added + _STAGE_STEP
    else:
        # Legacy sequential
        delay = 0.0
        for el in elements:
            if not isinstance(el, dict):
                continue
            if el.get("shape") in ("arrow", "curved_arrow", "double_arrow", "elbow_arrow"):
                has_arrow = True
            if el.get("shape") == "double_arrow":
                has_double_arrow = True
            svg_str, delay = _render_shape(el, delay)
            if svg_str:
                parts.append(svg_str)

    if not parts:
        return ""

    defs_block = _make_defs(has_arrow, has_double_arrow)
    svg_body   = "\n".join(parts)

    return (
        '<!DOCTYPE html><html>'
        '<head><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">'
        f'<style>*{{margin:0;padding:0}}body{{background:{BG_COLOR};display:flex;'
        'align-items:center;justify-content:center;width:100%;height:100vh}}'
        'svg{max-width:100%}</style></head>'
        '<body>'
        '<svg viewBox="0 0 400 300" width="100%" xmlns="http://www.w3.org/2000/svg">'
        f'{defs_block}'
        f'{svg_body}'
        '</svg></body></html>'
    )
