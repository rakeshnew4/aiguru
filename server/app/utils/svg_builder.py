"""
svg_builder.py — Server-side animated SVG HTML builder.

Architecture (v2):
  LLM  → diagram_type + data   (structured, no raw coords)
  Python layout engine → shapes list   (deterministic, no overlap)
  build_animated_svg  → full animated HTML   (SMIL, chalk style)
  Android WebView     → renders HTML as-is   (zero client-side JS)

Canvas: 400 × 300 viewBox.
Supported diagram types (Python-generated, collision-free):
  triangle, circle_radius, rectangle_area, line_graph, flow, comparison
Legacy fallback: raw svg_elements still accepted for cached lessons.
"""

import json
import math

# ── Colour palette ────────────────────────────────────────────────────────────
STROKE      = "#F0EDD0"   # chalk white
LABEL_COLOR = "#F5E3A0"   # chalk yellow
ACCENT      = "#4FC3F7"   # sky blue
HIGHLIGHT   = "#FF6B6B"   # warm red / emphasis
DIM         = "#8BAB8B"   # muted green
BG_COLOR    = "#1A2B1A"   # blackboard green

COLORS = {
    "primary":   STROKE,
    "label":     LABEL_COLOR,
    "highlight": HIGHLIGHT,
    "secondary": ACCENT,
    "dim":       DIM,
}


# ── Layout utilities ──────────────────────────────────────────────────────────

def _clamp(val: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, val))

# Grid columns/rows for snapping when converting legacy raw coords
_GRID_X = [50.0, 150.0, 200.0, 250.0, 350.0]
_GRID_Y = [60.0, 120.0, 150.0, 190.0, 240.0]

def _snap_x(val: float) -> float:
    return min(_GRID_X, key=lambda g: abs(g - val))

def _snap_y(val: float) -> float:
    return min(_GRID_Y, key=lambda g: abs(g - val))


def _escape(value: str) -> str:
    """HTML-escape a label value — prevents XSS via LLM label strings."""
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def _resolve_color(key: str) -> str:
    return COLORS.get(key, STROKE)


# ── Shape → SVG string (with SMIL animation) ─────────────────────────────────

def _render_shape(el: dict, delay: float) -> tuple[str, float]:
    """
    Convert one shape dict to an SVG element string.
    Returns (svg_string, new_delay).
    Supports: line, arrow, circle, rect, text.
    """
    shape = el.get("shape", "")
    color = _resolve_color(el.get("color", "primary"))

    if shape in ("line", "arrow"):
        x1 = _clamp(float(el.get("x1", 0)), 2, 398)
        y1 = _clamp(float(el.get("y1", 0)), 2, 298)
        x2 = _clamp(float(el.get("x2", 0)), 2, 398)
        y2 = _clamp(float(el.get("y2", 0)), 2, 298)
        length = math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2) or 1
        marker = ' marker-end="url(#arrow)"' if shape == "arrow" else ""
        svg = (
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
            f'stroke="{color}" stroke-width="2.5" stroke-linecap="round"{marker} '
            f'stroke-dasharray="{length:.2f}" stroke-dashoffset="{length:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{length:.2f}" to="0" '
            f'dur="0.45s" begin="{delay:.2f}s" fill="freeze"/></line>'
        )
        return svg, delay + 0.38

    elif shape == "circle":
        cx = _clamp(float(el.get("cx", 200)), 10, 390)
        cy = _clamp(float(el.get("cy", 150)), 10, 290)
        r  = _clamp(float(el.get("r",  40)),  4, 120)
        circ = 2 * math.pi * r
        svg = (
            f'<circle cx="{cx}" cy="{cy}" r="{r}" '
            f'stroke="{color}" stroke-width="2.5" fill="none" '
            f'stroke-dasharray="{circ:.2f}" stroke-dashoffset="{circ:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{circ:.2f}" to="0" '
            f'dur="0.55s" begin="{delay:.2f}s" fill="freeze"/></circle>'
        )
        return svg, delay + 0.48

    elif shape == "rect":
        x = _clamp(float(el.get("x", 10)), 2, 380)
        y = _clamp(float(el.get("y", 10)), 2, 280)
        w = _clamp(float(el.get("w", 60)), 4, 396 - x)
        h = _clamp(float(el.get("h", 40)), 4, 296 - y)
        svg = (
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" '
            f'stroke="{color}" stroke-width="2.5" fill="none" rx="4" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.35s" begin="{delay:.2f}s" fill="freeze"/></rect>'
        )
        return svg, delay + 0.30

    elif shape == "text":
        x   = _clamp(float(el.get("x", 200)), 4, 396)
        y   = _clamp(float(el.get("y", 150)), 12, 298)
        val = _escape(el.get("value", ""))
        size = int(el.get("size", 13))
        fill = _resolve_color(el.get("color", "label"))
        bold = ' font-weight="bold"' if el.get("bold") else ""
        svg = (
            f'<text x="{x}" y="{y}" font-family="serif" font-size="{size}" '
            f'fill="{fill}" text-anchor="middle" opacity="0"{bold}>{val}'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.35s" begin="{delay:.2f}s" fill="freeze"/></text>'
        )
        return svg, delay + 0.28

    elif shape == "dot":
        cx = _clamp(float(el.get("cx", 200)), 5, 395)
        cy = _clamp(float(el.get("cy", 150)), 5, 295)
        r  = float(el.get("r", 4))
        svg = (
            f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{color}" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.25s" begin="{delay:.2f}s" fill="freeze"/></circle>'
        )
        return svg, delay + 0.20

    return "", delay


# ── Structured diagram renderers (Python-generated coordinates) ───────────────

def _render_triangle(data: dict) -> list:
    """Fixed-position equilateral-ish triangle with vertex labels."""
    labels = data.get("labels") or ["A", "B", "C"]
    while len(labels) < 3:
        labels.append("")

    A = (200, 55)
    B = (90,  235)
    C = (310, 235)

    shapes = [
        {"shape": "line",  "x1": A[0], "y1": A[1], "x2": B[0], "y2": B[1]},
        {"shape": "line",  "x1": B[0], "y1": B[1], "x2": C[0], "y2": C[1]},
        {"shape": "line",  "x1": C[0], "y1": C[1], "x2": A[0], "y2": A[1]},
        {"shape": "text",  "x": A[0],      "y": A[1] - 12, "value": labels[0], "color": "label", "bold": True},
        {"shape": "text",  "x": B[0] - 15, "y": B[1] + 16, "value": labels[1], "color": "label", "bold": True},
        {"shape": "text",  "x": C[0] + 15, "y": C[1] + 16, "value": labels[2], "color": "label", "bold": True},
    ]
    if data.get("show_height"):
        # Height from A perpendicular to BC (BC is horizontal so foot is (A[0], B[1]))
        foot = (A[0], B[1])
        shapes.append({"shape": "line",  "x1": A[0],    "y1": A[1],    "x2": foot[0], "y2": foot[1], "color": "secondary"})
        shapes.append({"shape": "text",  "x": A[0] + 14, "y": (A[1] + foot[1]) // 2, "value": "h", "color": "secondary"})
    return shapes


def _render_circle_radius(data: dict) -> list:
    cx, cy = 200, 148
    r     = _clamp(float(data.get("radius", 70)), 20, 110)
    label = str(data.get("label") or "r")
    edge_x = cx + r
    shapes = [
        {"shape": "circle", "cx": cx, "cy": cy, "r": r},
        {"shape": "dot",    "cx": cx, "cy": cy, "r": 4, "color": "label"},
        {"shape": "line",   "x1": cx, "y1": cy, "x2": edge_x, "y2": cy, "color": "secondary"},
        {"shape": "text",   "x": cx + r // 2, "y": cy - 10, "value": label, "color": "secondary", "bold": True},
        {"shape": "text",   "x": cx,           "y": cy + r + 22, "value": "Centre O", "color": "dim"},
    ]
    return shapes


def _render_rectangle_area(data: dict) -> list:
    w_val = _clamp(float(data.get("width",  100)), 20, 280)
    h_val = _clamp(float(data.get("height", 70)),  15, 200)
    # Centre the rect on the canvas
    x = (400 - w_val) / 2
    y = (300 - h_val) / 2
    shapes = [
        {"shape": "rect", "x": x, "y": y, "w": w_val, "h": h_val},
        # Width label below bottom edge
        {"shape": "text", "x": x + w_val / 2, "y": y + h_val + 20, "value": f"w = {int(w_val)}", "color": "label"},
        # Height label right of right edge
        {"shape": "text", "x": x + w_val + 30, "y": y + h_val / 2, "value": f"h = {int(h_val)}", "color": "label"},
        # Area formula centred inside
        {"shape": "text", "x": 200, "y": y + h_val / 2 + 5, "value": "A = w × h", "color": "secondary", "bold": True},
    ]
    return shapes


def _render_line_graph(data: dict) -> list:
    raw_pts  = data.get("points") or [[0, 0], [1, 2], [2, 4]]
    x_label  = str(data.get("x_label") or "x")
    y_label  = str(data.get("y_label") or "y")

    # Canvas margins
    OX, OY = 60, 240   # origin

    pts = [(float(p[0]), float(p[1])) for p in raw_pts if len(p) >= 2]
    if not pts:
        return []

    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    x_range = (max(xs) - min(xs)) or 1
    y_range = (max(ys) - min(ys)) or 1
    # Scale to fit within (OX..350, 50..OY)
    def to_canvas(x, y):
        cx = OX + (x - min(xs)) / x_range * (350 - OX)
        cy = OY - (y - min(ys)) / y_range * (OY - 50)
        return _clamp(cx, OX, 355), _clamp(cy, 45, OY)

    shapes = [
        # Axes
        {"shape": "line", "x1": OX, "y1": OY, "x2": 355, "y2": OY, "color": "dim"},
        {"shape": "line", "x1": OX, "y1": OY, "x2": OX,  "y2": 45, "color": "dim"},
        # Axis labels
        {"shape": "text", "x": 355, "y": OY + 16, "value": x_label, "color": "label"},
        {"shape": "text", "x": OX,  "y": 38,      "value": y_label, "color": "label"},
    ]
    # Connect points with lines then plot dots
    canvas_pts = [to_canvas(x, y) for x, y in pts]
    for i in range(len(canvas_pts) - 1):
        x1, y1 = canvas_pts[i]
        x2, y2 = canvas_pts[i + 1]
        shapes.append({"shape": "line", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "color": "secondary"})
    for cx, cy in canvas_pts:
        shapes.append({"shape": "dot", "cx": cx, "cy": cy, "r": 5, "color": "highlight"})
    return shapes


def _render_flow(data: dict) -> list:
    steps = (data.get("steps") or [])[:5]
    if not steps:
        return []

    n        = len(steps)
    box_w    = 200
    box_h    = 34
    spacing  = 12
    total_h  = n * box_h + (n - 1) * spacing
    start_y  = (300 - total_h) / 2
    cx       = 200

    shapes = []
    for i, label in enumerate(steps):
        bx = cx - box_w / 2
        by = start_y + i * (box_h + spacing)
        shapes.append({"shape": "rect",  "x": bx, "y": by, "w": box_w, "h": box_h})
        shapes.append({"shape": "text",  "x": cx, "y": by + box_h / 2 + 5,
                        "value": str(label)[:30], "color": "label", "bold": True})
        if i < n - 1:
            arrow_y_start = by + box_h
            arrow_y_end   = by + box_h + spacing
            shapes.append({"shape": "arrow", "x1": cx, "y1": arrow_y_start,
                            "x2": cx, "y2": arrow_y_end, "color": "secondary"})
    return shapes


def _render_comparison(data: dict) -> list:
    left  = str(data.get("left",  "A"))[:20]
    right = str(data.get("right", "B"))[:20]

    shapes = [
        # Left box
        {"shape": "rect", "x": 20,  "y": 80, "w": 150, "h": 140, "color": "secondary"},
        {"shape": "text", "x": 95,  "y": 150, "value": left, "color": "label", "bold": True},
        # Right box
        {"shape": "rect", "x": 230, "y": 80, "w": 150, "h": 140, "color": "highlight"},
        {"shape": "text", "x": 305, "y": 150, "value": right, "color": "label", "bold": True},
        # "vs" in the middle
        {"shape": "text", "x": 200, "y": 155, "value": "vs", "color": "dim", "bold": True, "size": 18},
    ]
    return shapes


# ── Dispatcher ────────────────────────────────────────────────────────────────

_RENDERERS = {
    "triangle":        _render_triangle,
    "circle_radius":   _render_circle_radius,
    "rectangle_area":  _render_rectangle_area,
    "line_graph":      _render_line_graph,
    "flow":            _render_flow,
    "comparison":      _render_comparison,
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


# ── SVG HTML builder ──────────────────────────────────────────────────────────

# Arrow marker added once in <defs>; reused by any shape with marker-end="url(#arrow)"
_ARROW_DEFS = (
    '<defs>'
    '<marker id="arrow" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">'
    '<path d="M0,0 L10,3.5 L0,7 Z" fill="{color}"/>'
    '</marker>'
    '</defs>'
).format(color=ACCENT)


def build_animated_svg(elements) -> str:
    """
    Convert a list of shape dicts (or JSON string) into a full animated HTML page.
    Supports: line, arrow, circle, rect, text, dot.
    Also accepts legacy raw-coord shapes (backward-compatible with cached lessons).
    Returns empty string on any error or empty input.
    """
    if isinstance(elements, str):
        try:
            elements = json.loads(elements)
        except (json.JSONDecodeError, TypeError):
            return ""

    if not elements or not isinstance(elements, list):
        return ""

    parts = []
    delay = 0.0
    has_arrow = False

    for el in elements:
        if not isinstance(el, dict):
            continue
        if el.get("shape") == "arrow":
            has_arrow = True
        svg_str, delay = _render_shape(el, delay)
        if svg_str:
            parts.append(svg_str)

    if not parts:
        return ""

    defs_block = _ARROW_DEFS if has_arrow else ""
    svg_body = "\n".join(parts)

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

