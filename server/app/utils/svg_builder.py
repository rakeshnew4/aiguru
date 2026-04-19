"""
svg_builder.py — Server-side animated SVG HTML builder.

Architecture (v3):
  LLM / auto-detect  → diagram_type + data   (structured, no raw coords)
  Python layout engine → shapes list         (deterministic, no overlap)
  build_animated_svg  → full animated HTML   (SMIL, chalk style)
  Android WebView     → renders HTML as-is   (zero client-side JS)

Canvas: 400 × 300 viewBox.
Supported diagram types:
  triangle, circle_radius, rectangle_area, line_graph, flow, comparison, cycle
New shapes in v3:
  polygon, ellipse, diamond, dashed_line, dotted_line, curved_arrow,
  double_arrow, elbow_arrow, rounded_rect, parallelogram, arc, angle_arc,
  highlight_box, axis_tick, grid_line
Animation storytelling:
  Each shape carries an animation_stage (int). Shapes at the same stage
  animate together; delay is stage-based so the diagram "teaches" step-by-step.
  Active/focus shapes use highlight color; inactive use dim.
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

# Base delay between animation stages (seconds)
_STAGE_STEP = 0.42


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
    """
    shape = el.get("shape", "")
    color = _resolve_color(el.get("color", "primary"))

    # ── line / arrow ─────────────────────────────────────────────────────────
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

    # ── dashed_line ───────────────────────────────────────────────────────────
    elif shape == "dashed_line":
        x1 = _clamp(float(el.get("x1", 0)), 2, 398)
        y1 = _clamp(float(el.get("y1", 0)), 2, 298)
        x2 = _clamp(float(el.get("x2", 0)), 2, 398)
        y2 = _clamp(float(el.get("y2", 0)), 2, 298)
        length = math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2) or 1
        svg = (
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
            f'stroke="{color}" stroke-width="2" stroke-dasharray="8 5" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.35s" begin="{delay:.2f}s" fill="freeze"/></line>'
        )
        return svg, delay + 0.30

    # ── dotted_line ───────────────────────────────────────────────────────────
    elif shape == "dotted_line":
        x1 = _clamp(float(el.get("x1", 0)), 2, 398)
        y1 = _clamp(float(el.get("y1", 0)), 2, 298)
        x2 = _clamp(float(el.get("x2", 0)), 2, 398)
        y2 = _clamp(float(el.get("y2", 0)), 2, 298)
        svg = (
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
            f'stroke="{color}" stroke-width="2" stroke-dasharray="2 4" '
            f'stroke-linecap="round" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.30s" begin="{delay:.2f}s" fill="freeze"/></line>'
        )
        return svg, delay + 0.25

    # ── double_arrow ──────────────────────────────────────────────────────────
    elif shape == "double_arrow":
        x1 = _clamp(float(el.get("x1", 0)), 2, 398)
        y1 = _clamp(float(el.get("y1", 0)), 2, 298)
        x2 = _clamp(float(el.get("x2", 0)), 2, 398)
        y2 = _clamp(float(el.get("y2", 0)), 2, 298)
        length = math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2) or 1
        svg = (
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
            f'stroke="{color}" stroke-width="2.5" stroke-linecap="round" '
            f'marker-start="url(#arrow_back)" marker-end="url(#arrow)" '
            f'stroke-dasharray="{length:.2f}" stroke-dashoffset="{length:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{length:.2f}" to="0" '
            f'dur="0.45s" begin="{delay:.2f}s" fill="freeze"/></line>'
        )
        return svg, delay + 0.38

    # ── elbow_arrow ───────────────────────────────────────────────────────────
    elif shape == "elbow_arrow":
        x1 = _clamp(float(el.get("x1", 0)), 2, 398)
        y1 = _clamp(float(el.get("y1", 0)), 2, 298)
        x2 = _clamp(float(el.get("x2", 0)), 2, 398)
        y2 = _clamp(float(el.get("y2", 0)), 2, 298)
        # Elbow: go horizontal then vertical
        mid_x = x2
        mid_y = y1
        d = f"M {x1} {y1} L {mid_x} {mid_y} L {x2} {y2}"
        path_len = abs(x2 - x1) + abs(y2 - y1) + 1
        svg = (
            f'<path d="{d}" stroke="{color}" stroke-width="2.5" fill="none" '
            f'stroke-linecap="round" stroke-linejoin="round" '
            f'marker-end="url(#arrow)" '
            f'stroke-dasharray="{path_len:.2f}" stroke-dashoffset="{path_len:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{path_len:.2f}" to="0" '
            f'dur="0.50s" begin="{delay:.2f}s" fill="freeze"/></path>'
        )
        return svg, delay + 0.42

    # ── curved_arrow ──────────────────────────────────────────────────────────
    elif shape == "curved_arrow":
        x1  = _clamp(float(el.get("x1", 100)), 2, 398)
        y1  = _clamp(float(el.get("y1", 150)), 2, 298)
        x2  = _clamp(float(el.get("x2", 300)), 2, 398)
        y2  = _clamp(float(el.get("y2", 150)), 2, 298)
        cx  = float(el.get("cpx", (x1 + x2) / 2))
        cy  = float(el.get("cpy", min(y1, y2) - 50))
        # Approximate path length via midpoint sampling
        path_len = math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2) * 1.3
        d = f"M {x1:.1f} {y1:.1f} Q {cx:.1f} {cy:.1f} {x2:.1f} {y2:.1f}"
        svg = (
            f'<path d="{d}" stroke="{color}" stroke-width="2.5" fill="none" '
            f'stroke-linecap="round" marker-end="url(#arrow)" '
            f'stroke-dasharray="{path_len:.2f}" stroke-dashoffset="{path_len:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{path_len:.2f}" to="0" '
            f'dur="0.55s" begin="{delay:.2f}s" fill="freeze"/></path>'
        )
        return svg, delay + 0.45

    # ── circle ────────────────────────────────────────────────────────────────
    elif shape == "circle":
        cx = _clamp(float(el.get("cx", 200)), 10, 390)
        cy = _clamp(float(el.get("cy", 150)), 10, 290)
        r  = _clamp(float(el.get("r",  40)),  4, 120)
        circ = 2 * math.pi * r
        fill_key = el.get("fill_color", "none")
        fill_val = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.18" if fill_val != "none" else "0"
        svg = (
            f'<circle cx="{cx}" cy="{cy}" r="{r}" '
            f'stroke="{color}" stroke-width="2.5" fill="{fill_val}" fill-opacity="{fill_opacity}" '
            f'stroke-dasharray="{circ:.2f}" stroke-dashoffset="{circ:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{circ:.2f}" to="0" '
            f'dur="0.55s" begin="{delay:.2f}s" fill="freeze"/></circle>'
        )
        return svg, delay + 0.48

    # ── ellipse ───────────────────────────────────────────────────────────────
    elif shape == "ellipse":
        cx = _clamp(float(el.get("cx", 200)), 10, 390)
        cy = _clamp(float(el.get("cy", 150)), 10, 290)
        rx = _clamp(float(el.get("rx",  60)),  4, 180)
        ry = _clamp(float(el.get("ry",  35)),  4, 140)
        # Approximate circumference (Ramanujan)
        a, b = rx, ry
        circ = math.pi * (3 * (a + b) - math.sqrt((3 * a + b) * (a + 3 * b)))
        fill_key = el.get("fill_color", "none")
        fill_val = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.18" if fill_val != "none" else "0"
        svg = (
            f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" '
            f'stroke="{color}" stroke-width="2.5" fill="{fill_val}" fill-opacity="{fill_opacity}" '
            f'stroke-dasharray="{circ:.2f}" stroke-dashoffset="{circ:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{circ:.2f}" to="0" '
            f'dur="0.55s" begin="{delay:.2f}s" fill="freeze"/></ellipse>'
        )
        return svg, delay + 0.48

    # ── rect ──────────────────────────────────────────────────────────────────
    elif shape == "rect":
        x = _clamp(float(el.get("x", 10)), 2, 380)
        y = _clamp(float(el.get("y", 10)), 2, 280)
        w = _clamp(float(el.get("w", 60)), 4, 396 - x)
        h = _clamp(float(el.get("h", 40)), 4, 296 - y)
        fill_key = el.get("fill_color", "none")
        fill_val = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.15" if fill_val != "none" else "0"
        svg = (
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" '
            f'stroke="{color}" stroke-width="2.5" fill="{fill_val}" fill-opacity="{fill_opacity}" rx="4" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.35s" begin="{delay:.2f}s" fill="freeze"/></rect>'
        )
        return svg, delay + 0.30

    # ── rounded_rect ──────────────────────────────────────────────────────────
    elif shape == "rounded_rect":
        x  = _clamp(float(el.get("x", 10)), 2, 380)
        y  = _clamp(float(el.get("y", 10)), 2, 280)
        w  = _clamp(float(el.get("w", 60)), 4, 396 - x)
        h  = _clamp(float(el.get("h", 40)), 4, 296 - y)
        rx = _clamp(float(el.get("rx", 12)), 0, min(w, h) / 2)
        fill_key = el.get("fill_color", "none")
        fill_val = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.15" if fill_val != "none" else "0"
        svg = (
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" ry="{rx}" '
            f'stroke="{color}" stroke-width="2.5" fill="{fill_val}" fill-opacity="{fill_opacity}" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.35s" begin="{delay:.2f}s" fill="freeze"/></rect>'
        )
        return svg, delay + 0.30

    # ── highlight_box ─────────────────────────────────────────────────────────
    elif shape == "highlight_box":
        x = _clamp(float(el.get("x", 10)), 2, 380)
        y = _clamp(float(el.get("y", 10)), 2, 280)
        w = _clamp(float(el.get("w", 60)), 4, 396 - x)
        h = _clamp(float(el.get("h", 40)), 4, 296 - y)
        svg = (
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" '
            f'stroke="{color}" stroke-width="3" fill="{color}" fill-opacity="0.20" '
            f'rx="6" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.30s" begin="{delay:.2f}s" fill="freeze"/></rect>'
        )
        return svg, delay + 0.25

    # ── diamond ───────────────────────────────────────────────────────────────
    elif shape == "diamond":
        cx = _clamp(float(el.get("cx", 200)), 20, 380)
        cy = _clamp(float(el.get("cy", 150)), 20, 280)
        hw = _clamp(float(el.get("hw",  60)), 10, 180)  # half-width
        hh = _clamp(float(el.get("hh",  40)), 10, 140)  # half-height
        pts = f"{cx},{cy - hh} {cx + hw},{cy} {cx},{cy + hh} {cx - hw},{cy}"
        perim = (math.sqrt(hw**2 + hh**2)) * 4
        fill_key = el.get("fill_color", "none")
        fill_val = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.18" if fill_val != "none" else "0"
        svg = (
            f'<polygon points="{pts}" '
            f'stroke="{color}" stroke-width="2.5" fill="{fill_val}" fill-opacity="{fill_opacity}" '
            f'stroke-dasharray="{perim:.2f}" stroke-dashoffset="{perim:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{perim:.2f}" to="0" '
            f'dur="0.50s" begin="{delay:.2f}s" fill="freeze"/></polygon>'
        )
        return svg, delay + 0.42

    # ── polygon (generic, N sides) ────────────────────────────────────────────
    elif shape == "polygon":
        cx   = _clamp(float(el.get("cx", 200)), 20, 380)
        cy   = _clamp(float(el.get("cy", 150)), 20, 280)
        r    = _clamp(float(el.get("r",   60)), 10, 130)
        sides = max(3, min(12, int(el.get("sides", 6))))
        offset = float(el.get("angle_offset", 0))  # rotation offset in degrees
        pts_list = []
        for i in range(sides):
            a = math.radians(360 * i / sides + offset - 90)
            pts_list.append(f"{cx + r * math.cos(a):.2f},{cy + r * math.sin(a):.2f}")
        pts = " ".join(pts_list)
        side_len = 2 * r * math.sin(math.pi / sides)
        perim = side_len * sides
        fill_key = el.get("fill_color", "none")
        fill_val = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.18" if fill_val != "none" else "0"
        svg = (
            f'<polygon points="{pts}" '
            f'stroke="{color}" stroke-width="2.5" fill="{fill_val}" fill-opacity="{fill_opacity}" '
            f'stroke-dasharray="{perim:.2f}" stroke-dashoffset="{perim:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{perim:.2f}" to="0" '
            f'dur="0.55s" begin="{delay:.2f}s" fill="freeze"/></polygon>'
        )
        return svg, delay + 0.45

    # ── parallelogram ─────────────────────────────────────────────────────────
    elif shape == "parallelogram":
        x      = _clamp(float(el.get("x",  80)), 2, 300)
        y      = _clamp(float(el.get("y",  100)), 2, 250)
        w      = _clamp(float(el.get("w", 180)), 20, 350)
        h      = _clamp(float(el.get("h",  50)), 10, 150)
        skew   = _clamp(float(el.get("skew", 20)), 0, 60)
        pts = (
            f"{x + skew:.1f},{y:.1f} {x + w:.1f},{y:.1f} "
            f"{x + w - skew:.1f},{y + h:.1f} {x:.1f},{y + h:.1f}"
        )
        perim = 2 * (w + math.sqrt(h**2 + skew**2))
        fill_key = el.get("fill_color", "none")
        fill_val = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.15" if fill_val != "none" else "0"
        svg = (
            f'<polygon points="{pts}" '
            f'stroke="{color}" stroke-width="2.5" fill="{fill_val}" fill-opacity="{fill_opacity}" '
            f'stroke-dasharray="{perim:.2f}" stroke-dashoffset="{perim:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{perim:.2f}" to="0" '
            f'dur="0.50s" begin="{delay:.2f}s" fill="freeze"/></polygon>'
        )
        return svg, delay + 0.42

    # ── arc ───────────────────────────────────────────────────────────────────
    elif shape == "arc":
        cx        = _clamp(float(el.get("cx", 200)), 10, 390)
        cy        = _clamp(float(el.get("cy", 150)), 10, 290)
        r         = _clamp(float(el.get("r",  60)),  5, 130)
        start_deg = float(el.get("start_deg", 0))
        end_deg   = float(el.get("end_deg", 180))
        sa = math.radians(start_deg - 90)
        ea = math.radians(end_deg - 90)
        x1 = cx + r * math.cos(sa)
        y1 = cy + r * math.sin(sa)
        x2 = cx + r * math.cos(ea)
        y2 = cy + r * math.sin(ea)
        large = 1 if (end_deg - start_deg) > 180 else 0
        arc_len = r * math.radians(abs(end_deg - start_deg))
        d = f"M {x1:.2f} {y1:.2f} A {r} {r} 0 {large} 1 {x2:.2f} {y2:.2f}"
        svg = (
            f'<path d="{d}" stroke="{color}" stroke-width="2.5" fill="none" '
            f'stroke-linecap="round" '
            f'stroke-dasharray="{arc_len:.2f}" stroke-dashoffset="{arc_len:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{arc_len:.2f}" to="0" '
            f'dur="0.45s" begin="{delay:.2f}s" fill="freeze"/></path>'
        )
        return svg, delay + 0.38

    # ── angle_arc (right-angle or degree arc for geometry) ────────────────────
    elif shape == "angle_arc":
        cx   = _clamp(float(el.get("cx", 200)), 10, 390)
        cy   = _clamp(float(el.get("cy", 150)), 10, 290)
        r    = _clamp(float(el.get("r",  20)),  6, 60)
        deg  = float(el.get("angle_deg", 90))
        rot  = float(el.get("rotation_deg", 0))  # how the angle is oriented
        if abs(deg - 90) < 1:
            # Draw a small square for right angle
            sa = math.radians(rot - 90)
            ea = math.radians(rot)
            px1 = cx + r * math.cos(sa)
            py1 = cy + r * math.sin(sa)
            px2 = cx + r * math.cos(sa) + r * math.cos(ea)
            py2 = cy + r * math.sin(sa) + r * math.sin(ea)
            px3 = cx + r * math.cos(ea)
            py3 = cy + r * math.sin(ea)
            d = f"M {px1:.1f},{py1:.1f} L {px2:.1f},{py2:.1f} L {px3:.1f},{py3:.1f}"
        else:
            sa = math.radians(rot - 90)
            ea = math.radians(rot - 90 + deg)
            x1 = cx + r * math.cos(sa)
            y1 = cy + r * math.sin(sa)
            x2 = cx + r * math.cos(ea)
            y2 = cy + r * math.sin(ea)
            large = 1 if deg > 180 else 0
            d = f"M {x1:.1f},{y1:.1f} A {r} {r} 0 {large} 1 {x2:.1f},{y2:.1f}"
        arc_len = r * math.radians(deg) if abs(deg - 90) >= 1 else r * 2
        svg = (
            f'<path d="{d}" stroke="{color}" stroke-width="2" fill="none" '
            f'stroke-linecap="round" stroke-linejoin="round" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.30s" begin="{delay:.2f}s" fill="freeze"/></path>'
        )
        return svg, delay + 0.25

    # ── grid_line ─────────────────────────────────────────────────────────────
    elif shape == "grid_line":
        x1 = _clamp(float(el.get("x1", 0)), 2, 398)
        y1 = _clamp(float(el.get("y1", 0)), 2, 298)
        x2 = _clamp(float(el.get("x2", 0)), 2, 398)
        y2 = _clamp(float(el.get("y2", 0)), 2, 298)
        svg = (
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
            f'stroke="{color}" stroke-width="0.8" stroke-dasharray="4 6" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="0.45" '
            f'dur="0.25s" begin="{delay:.2f}s" fill="freeze"/></line>'
        )
        return svg, delay + 0.05  # grid lines add minimal delay

    # ── axis_tick ─────────────────────────────────────────────────────────────
    elif shape == "axis_tick":
        x  = _clamp(float(el.get("x",  60)), 2, 398)
        y  = _clamp(float(el.get("y", 240)), 2, 298)
        axis = el.get("axis", "x")  # "x" → vertical tick, "y" → horizontal tick
        size = float(el.get("size", 5))
        if axis == "x":
            svg = (
                f'<line x1="{x}" y1="{y - size}" x2="{x}" y2="{y + size}" '
                f'stroke="{color}" stroke-width="1.5" opacity="0">'
                f'<animate attributeName="opacity" from="0" to="1" '
                f'dur="0.20s" begin="{delay:.2f}s" fill="freeze"/></line>'
            )
        else:
            svg = (
                f'<line x1="{x - size}" y1="{y}" x2="{x + size}" y2="{y}" '
                f'stroke="{color}" stroke-width="1.5" opacity="0">'
                f'<animate attributeName="opacity" from="0" to="1" '
                f'dur="0.20s" begin="{delay:.2f}s" fill="freeze"/></line>'
            )
        return svg, delay + 0.05

    # ── text ──────────────────────────────────────────────────────────────────
    elif shape == "text":
        x   = _clamp(float(el.get("x", 200)), 4, 396)
        y   = _clamp(float(el.get("y", 150)), 12, 298)
        val = _escape(el.get("value", ""))
        size = int(el.get("size", 13))
        fill = _resolve_color(el.get("color", "label"))
        bold = ' font-weight="bold"' if el.get("bold") else ""
        anchor = el.get("anchor", "middle")
        svg = (
            f'<text x="{x}" y="{y}" font-family="serif" font-size="{size}" '
            f'fill="{fill}" text-anchor="{anchor}" opacity="0"{bold}>{val}'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.35s" begin="{delay:.2f}s" fill="freeze"/></text>'
        )
        return svg, delay + 0.28

    # ── dot ───────────────────────────────────────────────────────────────────
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
    # ── emoji ─────────────────────────────────────────────────────────────────
    elif shape == "emoji":
        ex = _clamp(float(el.get("x", 200)), 8, 392)
        ey = _clamp(float(el.get("y", 150)), 16, 296)
        echar = str(el.get("value", "⭐"))[:8]  # up to 2 emoji chars
        esize = _clamp(int(el.get("size", 26)), 12, 48)
        anchor = el.get("anchor", "middle")
        svg = (
            f'<text x="{ex}" y="{ey}" font-size="{esize}" text-anchor="{anchor}" opacity="0">'
            f'{echar}'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.30s" begin="{delay:.2f}s" fill="freeze"/></text>'
        )
        return svg, delay + 0.25
    return "", delay


# ── Structured diagram renderers (Python-generated coordinates) ───────────────

def _render_triangle(data: dict) -> list:
    """Fixed-position equilateral-ish triangle with vertex labels.
    Supports:
      show_height   → altitude from A to BC with right-angle marker
      show_incircle → inscribed circle touching all 3 sides (incircle)
      show_circumcircle → circumscribed circle passing through all vertices
      show_median   → median from A to midpoint of BC
    """
    labels = data.get("labels") or ["A", "B", "C"]
    while len(labels) < 3:
        labels.append("")

    A = (200.0, 55.0)
    B = (90.0,  235.0)
    C = (310.0, 235.0)

    shapes = [
        {"shape": "line", "x1": A[0], "y1": A[1], "x2": B[0], "y2": B[1], "color": "highlight", "animation_stage": 0},
        {"shape": "line", "x1": B[0], "y1": B[1], "x2": C[0], "y2": C[1], "color": "highlight", "animation_stage": 0},
        {"shape": "line", "x1": C[0], "y1": C[1], "x2": A[0], "y2": A[1], "color": "highlight", "animation_stage": 0},
        {"shape": "dot",  "cx": A[0], "cy": A[1],  "r": 4, "color": "label", "animation_stage": 1},
        {"shape": "dot",  "cx": B[0], "cy": B[1],  "r": 4, "color": "label", "animation_stage": 1},
        {"shape": "dot",  "cx": C[0], "cy": C[1],  "r": 4, "color": "label", "animation_stage": 1},
        {"shape": "text", "x": A[0],      "y": A[1] - 14, "value": labels[0], "color": "label", "bold": True, "size": 15, "animation_stage": 1},
        {"shape": "text", "x": B[0] - 18, "y": B[1] + 18, "value": labels[1], "color": "label", "bold": True, "size": 15, "animation_stage": 1},
        {"shape": "text", "x": C[0] + 18, "y": C[1] + 18, "value": labels[2], "color": "label", "bold": True, "size": 15, "animation_stage": 1},
    ]

    # ── Side lengths (for incircle / circumcircle calculations) ──────────────
    a = math.sqrt((C[0]-B[0])**2 + (C[1]-B[1])**2)   # BC (opposite A)
    b = math.sqrt((A[0]-C[0])**2 + (A[1]-C[1])**2)   # CA (opposite B)
    c = math.sqrt((B[0]-A[0])**2 + (B[1]-A[1])**2)   # AB (opposite C)
    perim = a + b + c
    s     = perim / 2  # semi-perimeter
    area  = abs((B[0]-A[0])*(C[1]-A[1]) - (C[0]-A[0])*(B[1]-A[1])) / 2

    if data.get("show_height"):
        foot = (A[0], B[1])
        shapes += [
            {"shape": "dashed_line", "x1": A[0], "y1": A[1], "x2": foot[0], "y2": foot[1], "color": "secondary", "animation_stage": 2},
            {"shape": "angle_arc",   "cx": foot[0], "cy": foot[1], "r": 12, "angle_deg": 90, "rotation_deg": 0, "color": "secondary", "animation_stage": 2},
            {"shape": "text",        "x": A[0] + 16, "y": (A[1] + foot[1]) / 2, "value": "h", "color": "secondary", "bold": True, "animation_stage": 2},
        ]

    if data.get("show_incircle"):
        # Incenter = weighted average of vertices by opposite side length
        ix = (a * A[0] + b * B[0] + c * C[0]) / perim
        iy = (a * A[1] + b * B[1] + c * C[1]) / perim
        # Inradius = Area / semi-perimeter
        ir = area / s
        shapes += [
            # The inscribed circle
            {"shape": "circle", "cx": round(ix, 1), "cy": round(iy, 1),
             "r": round(ir, 1), "color": "secondary", "fill_color": "secondary",
             "animation_stage": 2},
            # Mark the incentre I
            {"shape": "dot", "cx": round(ix, 1), "cy": round(iy, 1),
             "r": 4, "color": "highlight", "animation_stage": 3},
            {"shape": "text", "x": round(ix, 1) + 12, "y": round(iy, 1) - 10,
             "value": "I", "color": "highlight", "bold": True, "size": 14,
             "animation_stage": 3},
            {"shape": "text", "x": round(ix, 1), "y": round(iy, 1) + ir + 16,
             "value": f"r = {ir:.0f}", "color": "label", "size": 11,
             "anchor": "middle", "animation_stage": 3},
        ]

    if data.get("show_circumcircle"):
        # Circumcentre via perpendicular bisectors
        ax, ay = A; bx, by = B; cx2, cy2 = C
        d_val = 2 * (ax*(by - cy2) + bx*(cy2 - ay) + cx2*(ay - by))
        if abs(d_val) > 0.01:
            ux = ((ax**2+ay**2)*(by-cy2) + (bx**2+by**2)*(cy2-ay) + (cx2**2+cy2**2)*(ay-by)) / d_val
            uy = ((ax**2+ay**2)*(cx2-bx) + (bx**2+by**2)*(ax-cx2) + (cx2**2+cy2**2)*(bx-ax)) / d_val
            ur = math.sqrt((ax-ux)**2 + (ay-uy)**2)
            shapes += [
                {"shape": "circle", "cx": round(ux,1), "cy": round(uy,1),
                 "r": round(ur,1), "color": "dim", "animation_stage": 2},
                {"shape": "dot",   "cx": round(ux,1), "cy": round(uy,1),
                 "r": 4, "color": "secondary", "animation_stage": 3},
                {"shape": "text",  "x": round(ux,1)+12, "y": round(uy,1)-8,
                 "value": "O", "color": "secondary", "bold": True, "size": 13,
                 "animation_stage": 3},
            ]

    if data.get("show_median"):
        # Median from A to midpoint M of BC
        mx = (B[0] + C[0]) / 2
        my = (B[1] + C[1]) / 2
        shapes += [
            {"shape": "dashed_line", "x1": A[0], "y1": A[1], "x2": mx, "y2": my,
             "color": "label", "animation_stage": 2},
            {"shape": "dot", "cx": mx, "cy": my, "r": 4,
             "color": "label", "animation_stage": 2},
            {"shape": "text", "x": mx + 12, "y": my + 6, "value": "M",
             "color": "label", "bold": True, "size": 13, "animation_stage": 3},
        ]

    return shapes


def _render_circle_radius(data: dict) -> list:
    cx, cy = 200, 148
    r     = _clamp(float(data.get("radius", 70)), 20, 110)
    label = str(data.get("label") or "r")
    edge_x = cx + r
    shapes = [
        {"shape": "circle", "cx": cx, "cy": cy, "r": r, "color": "secondary", "fill_color": "secondary", "animation_stage": 0},
        {"shape": "dot",    "cx": cx, "cy": cy, "r": 5, "color": "label", "animation_stage": 1},
        {"shape": "line",   "x1": cx, "y1": cy, "x2": edge_x, "y2": cy, "color": "highlight", "animation_stage": 1},
        {"shape": "text",   "x": cx + r // 2, "y": cy - 12, "value": label, "color": "highlight", "bold": True, "size": 15, "animation_stage": 2},
        {"shape": "text",   "x": cx, "y": cy + r + 22, "value": "Centre O", "color": "label", "animation_stage": 2},
    ]
    return shapes


def _render_rectangle_area(data: dict) -> list:
    w_val = _clamp(float(data.get("width",  100)), 20, 260)
    h_val = _clamp(float(data.get("height", 70)),  15, 180)
    x = (400 - w_val) / 2
    y = (300 - h_val) / 2 - 10
    shapes = [
        {"shape": "rect", "x": x, "y": y, "w": w_val, "h": h_val,
         "color": "secondary", "fill_color": "secondary", "animation_stage": 0},
        {"shape": "text", "x": x + w_val / 2, "y": y + h_val + 20,
         "value": f"w = {int(w_val)}", "color": "highlight", "bold": True, "animation_stage": 1},
        {"shape": "text", "x": min(x + w_val + 32, 390), "y": y + h_val / 2 + 5,
         "value": f"h = {int(h_val)}", "color": "highlight", "bold": True, "animation_stage": 1},
        {"shape": "text", "x": 200, "y": y + h_val / 2 + 5,
         "value": "A = w × h", "color": "label", "bold": True, "size": 14, "animation_stage": 2},
    ]
    return shapes


def _render_line_graph(data: dict) -> list:
    raw_pts  = data.get("points") or [[0, 0], [1, 2], [2, 4]]
    x_label  = str(data.get("x_label") or "x")
    y_label  = str(data.get("y_label") or "y")

    OX, OY = 60, 240

    pts = [(float(p[0]), float(p[1])) for p in raw_pts if len(p) >= 2]
    if not pts:
        return []

    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    x_range = (max(xs) - min(xs)) or 1
    y_range = (max(ys) - min(ys)) or 1

    def to_canvas(x, y):
        cx = OX + (x - min(xs)) / x_range * (350 - OX)
        cy = OY - (y - min(ys)) / y_range * (OY - 50)
        return _clamp(cx, OX, 355), _clamp(cy, 45, OY)

    shapes = [
        {"shape": "arrow", "x1": OX, "y1": OY, "x2": 358, "y2": OY, "color": "primary", "animation_stage": 0},
        {"shape": "arrow", "x1": OX, "y1": OY, "x2": OX,  "y2": 42, "color": "primary", "animation_stage": 0},
        {"shape": "text",  "x": 362, "y": OY + 5,  "value": x_label, "color": "label", "bold": True, "animation_stage": 0},
        {"shape": "text",  "x": OX,  "y": 36,       "value": y_label, "color": "label", "bold": True, "animation_stage": 0},
        {"shape": "text",  "x": OX - 10, "y": OY + 15, "value": "0", "color": "dim", "animation_stage": 0},
    ]
    canvas_pts = [to_canvas(px, py) for px, py in pts]
    for i, (cpx, cpy) in enumerate(canvas_pts):
        shapes.append({"shape": "axis_tick", "x": cpx, "y": OY, "axis": "x", "color": "dim", "animation_stage": 0})
    for i in range(len(canvas_pts) - 1):
        x1, y1 = canvas_pts[i]
        x2, y2 = canvas_pts[i + 1]
        shapes.append({"shape": "line", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "color": "secondary", "animation_stage": i + 1})
    for i, (px, py) in enumerate(canvas_pts):
        shapes.append({"shape": "dot", "cx": px, "cy": py, "r": 6, "color": "highlight", "animation_stage": i + 1})
    return shapes


def _render_flow(data: dict) -> list:
    """
    Visual journey layout — numbered circles connected by arrows.
    NOT a boring vertical flowchart.
    1-3 steps: horizontal row.
    4 steps: 2×2 grid with loop arrows.
    5 steps: 3-top + 2-bottom staircase.
    """
    steps = (data.get("steps") or [])[:5]
    n = len(steps)
    if not steps:
        return []

    title     = str(data.get("title", ""))[:30]
    node_r    = 26
    colors    = ["secondary", "highlight", "label", "secondary", "highlight"]
    emoji_map = ["1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣"]

    if n <= 3:
        # Horizontal, centred vertically
        span   = 280
        step_x = span / max(n - 1, 1)
        sx0    = (400 - span) / 2
        positions = [(sx0 + i * step_x, 148.0) for i in range(n)]
    elif n == 4:
        # 2×2 square
        positions = [(110.0, 100.0), (290.0, 100.0),
                     (290.0, 212.0), (110.0, 212.0)]
    else:
        # 3-top, 2-bottom staircase
        positions = [(70.0, 95.0), (200.0, 80.0), (330.0, 95.0),
                     (145.0, 218.0), (255.0, 218.0)]

    shapes = []

    # Optional title
    if title:
        shapes.append({"shape": "text", "x": 200, "y": 20,
                        "value": title, "color": "label", "bold": True,
                        "size": 12, "anchor": "middle", "animation_stage": 0})

    for i, (px, py) in enumerate(positions):
        color = colors[i % len(colors)]

        # Node circle
        shapes.append({"shape": "circle", "cx": px, "cy": py, "r": node_r,
                        "color": color, "fill_color": color, "animation_stage": i})

        # Step number inside circle
        shapes.append({"shape": "text", "x": px, "y": py + 6,
                        "value": str(i + 1), "color": "primary", "bold": True,
                        "size": 16, "anchor": "middle", "animation_stage": i})

        # Step label below (or above if near bottom edge)
        label_y = py + node_r + 15
        if label_y > 288:
            label_y = py - node_r - 8
        shapes.append({"shape": "text", "x": px, "y": label_y,
                        "value": str(steps[i])[:22], "color": color,
                        "size": 10, "anchor": "middle", "animation_stage": i})

        # Arrow to next node
        if i < n - 1:
            nx2, ny2 = positions[i + 1]
            dx, dy   = nx2 - px, ny2 - py
            dist     = math.sqrt(dx ** 2 + dy ** 2) or 1
            sx_a = px  + dx / dist * (node_r + 4)
            sy_a = py  + dy / dist * (node_r + 4)
            ex_a = nx2 - dx / dist * (node_r + 5)
            ey_a = ny2 - dy / dist * (node_r + 5)
            shapes.append({"shape": "arrow", "x1": sx_a, "y1": sy_a,
                            "x2": ex_a, "y2": ey_a,
                            "color": "dim", "animation_stage": i})

    return shapes


def _render_comparison(data: dict) -> list:
    """
    Side-by-side comparison with background panels, a 'vs' badge in the
    centre, and coloured bullet-dot rows that animate one row at a time.
    """
    left      = str(data.get("left",  "A"))[:18]
    right     = str(data.get("right", "B"))[:18]
    left_pts  = [str(p)[:26] for p in (data.get("left_points")  or [])][:4]
    right_pts = [str(p)[:26] for p in (data.get("right_points") or [])][:4]

    shapes = [
        # Left tinted background panel
        {"shape": "rounded_rect", "x": 6,   "y": 25, "w": 186, "h": 268,
         "color": "secondary", "fill_color": "secondary", "rx": 12, "animation_stage": 0},
        # Right tinted background panel
        {"shape": "rounded_rect", "x": 208, "y": 25, "w": 186, "h": 268,
         "color": "highlight", "fill_color": "highlight", "rx": 12, "animation_stage": 0},
        # Centre divider line
        {"shape": "dashed_line", "x1": 200, "y1": 30, "x2": 200, "y2": 285,
         "color": "label", "animation_stage": 0},
        # "vs" badge
        {"shape": "circle", "cx": 200, "cy": 155, "r": 19,
         "color": "label", "fill_color": "label", "animation_stage": 0},
        {"shape": "text", "x": 200, "y": 161, "value": "vs",
         "color": "primary", "bold": True, "size": 12,
         "anchor": "middle", "animation_stage": 0},
        # Header labels
        {"shape": "text", "x": 99,  "y": 47, "value": left,
         "color": "primary", "bold": True, "size": 13,
         "anchor": "middle", "animation_stage": 0},
        {"shape": "text", "x": 301, "y": 47, "value": right,
         "color": "primary", "bold": True, "size": 13,
         "anchor": "middle", "animation_stage": 0},
    ]

    n_rows = max(len(left_pts), len(right_pts))
    for i in range(n_rows):
        row_y = 78 + i * 48
        # Left bullet
        if i < len(left_pts):
            shapes.append({"shape": "dot", "cx": 18, "cy": row_y - 2, "r": 5,
                            "color": "primary", "animation_stage": i + 1})
            shapes.append({"shape": "text", "x": 27, "y": row_y + 3,
                            "value": left_pts[i], "color": "primary",
                            "size": 10, "anchor": "start", "animation_stage": i + 1})
        # Right bullet
        if i < len(right_pts):
            shapes.append({"shape": "dot", "cx": 214, "cy": row_y - 2, "r": 5,
                            "color": "primary", "animation_stage": i + 1})
            shapes.append({"shape": "text", "x": 223, "y": row_y + 3,
                            "value": right_pts[i], "color": "primary",
                            "size": 10, "anchor": "start", "animation_stage": i + 1})
    return shapes


def _render_cycle(data: dict) -> list:
    """
    Circular arrangement of steps with curved arrows between nodes.
    Center hub circle shows the cycle title.
    Max 6 steps; ideal 3–5.
    """
    steps = (data.get("steps") or [])[:6]
    title = str(data.get("title", "cycle"))[:14]
    n = len(steps)
    if n < 2:
        return []

    cx, cy    = 200, 148
    r_layout  = 88   # distance from centre to node centres
    node_r    = 26
    colors    = ["highlight", "secondary", "label",
                 "highlight", "secondary", "label"]

    node_positions = []
    for i in range(n):
        angle = math.radians(360 * i / n - 90)
        node_positions.append(
            (cx + r_layout * math.cos(angle),
             cy + r_layout * math.sin(angle))
        )

    shapes = []

    # ── Centre hub ────────────────────────────────────────────────────────────
    hub_r = 30
    shapes.append({"shape": "circle", "cx": cx, "cy": cy, "r": hub_r,
                   "color": "label", "fill_color": "label", "animation_stage": 0})
    shapes.append({"shape": "text", "x": cx, "y": cy + 5, "value": title,
                   "color": "primary", "bold": True, "size": 9,
                   "anchor": "middle", "animation_stage": 0})

    # ── Nodes + arrows ────────────────────────────────────────────────────────
    for i, (nx, ny) in enumerate(node_positions):
        color = colors[i % len(colors)]
        shapes.append({"shape": "circle", "cx": nx, "cy": ny, "r": node_r,
                        "color": color, "fill_color": color, "animation_stage": i + 1})
        shapes.append({"shape": "text", "x": nx, "y": ny + 5,
                        "value": str(steps[i])[:12], "color": "primary",
                        "bold": True, "size": 9,
                        "anchor": "middle", "animation_stage": i + 1})

        # Curved arrow: node i → node (i+1) % n
        next_i       = (i + 1) % n
        nxt_x, nxt_y = node_positions[next_i]
        mid_x = (nx + nxt_x) / 2
        mid_y = (ny + nxt_y) / 2
        out_dx = mid_x - cx
        out_dy = mid_y - cy
        out_len = math.sqrt(out_dx ** 2 + out_dy ** 2) or 1
        push = r_layout * 0.52
        cpx = mid_x + push * out_dx / out_len
        cpy = mid_y + push * out_dy / out_len

        def _sh(ax, ay, bx, by, margin):
            ddx, ddy = bx - ax, by - ay
            dd = math.sqrt(ddx ** 2 + ddy ** 2) or 1
            return ax + ddx / dd * margin, ay + ddy / dd * margin

        sx, sy = _sh(nx,    ny,    cpx, cpy, node_r + 4)
        ex, ey = _sh(nxt_x, nxt_y, cpx, cpy, node_r + 4)
        shapes.append({"shape": "curved_arrow",
                        "x1": sx, "y1": sy, "x2": ex, "y2": ey,
                        "cpx": cpx, "cpy": cpy,
                        "color": "dim", "animation_stage": i + 1})

    return shapes


def _render_labeled_diagram(data: dict) -> list:
    """
    Anatomy / structure diagram — a central shape with radiating dashed
    pointer lines to labelled parts.  Great for cells, atoms, machines.
    data keys:
      center       → label inside central shape (str)
      center_shape → "circle" | "ellipse"  (default circle)
      parts        → list of part-name strings (max 6)
    """
    center_label = str(data.get("center", ""))[:16]
    center_shape = data.get("center_shape", "circle")
    parts        = [str(p)[:20] for p in (data.get("parts") or [])][:6]
    n            = len(parts)

    cx, cy   = 200, 148
    r_label  = 108   # distance from centre to label anchor
    inner_r  = 47    # where dashed line starts (outside central shape)

    shapes = []

    # Central shape
    if center_shape == "ellipse":
        shapes.append({"shape": "ellipse", "cx": cx, "cy": cy,
                        "rx": 52, "ry": 36,
                        "color": "secondary", "fill_color": "secondary",
                        "animation_stage": 0})
    else:
        shapes.append({"shape": "circle", "cx": cx, "cy": cy, "r": 42,
                        "color": "secondary", "fill_color": "secondary",
                        "animation_stage": 0})
    if center_label:
        shapes.append({"shape": "text", "x": cx, "y": cy + 5,
                        "value": center_label, "color": "primary",
                        "bold": True, "size": 12,
                        "anchor": "middle", "animation_stage": 0})

    for i, part in enumerate(parts):
        angle = math.radians(360 * i / max(n, 1) - 90)
        lx = cx + r_label * math.cos(angle)
        ly = cy + r_label * math.sin(angle)
        # clamp to canvas
        lx = _clamp(lx, 10, 390)
        ly = _clamp(ly, 14, 288)
        # line start just outside central shape
        sx = cx + inner_r * math.cos(angle)
        sy = cy + inner_r * math.sin(angle)
        shapes.append({"shape": "dashed_line",
                        "x1": sx, "y1": sy, "x2": lx, "y2": ly,
                        "color": "dim", "animation_stage": i + 1})
        shapes.append({"shape": "dot", "cx": lx, "cy": ly, "r": 4,
                        "color": "highlight", "animation_stage": i + 1})
        anchor = "start" if lx >= cx else "end"
        tx = lx + (9 if lx >= cx else -9)
        shapes.append({"shape": "text", "x": tx, "y": ly + 5,
                        "value": part, "color": "label", "size": 11,
                        "anchor": anchor, "animation_stage": i + 1})

    return shapes


# ── Dispatcher ────────────────────────────────────────────────────────────────

_RENDERERS = {
    "triangle":        _render_triangle,
    "circle_radius":   _render_circle_radius,
    "rectangle_area":  _render_rectangle_area,
    "line_graph":      _render_line_graph,
    "flow":            _render_flow,
    "comparison":      _render_comparison,
    "cycle":           _render_cycle,
    "labeled_diagram": _render_labeled_diagram,
    "anatomy":         _render_labeled_diagram,   # alias
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

    # ── Separate staged vs legacy shapes ──────────────────────────────────────
    has_stages = any(isinstance(el, dict) and "animation_stage" in el for el in elements)

    parts = []
    has_arrow        = False
    has_double_arrow = False

    if has_stages:
        # Group by stage, then render each stage's shapes together
        # Delay advances per-stage, not per-shape (all shapes in a stage start at the same base delay)
        from collections import defaultdict
        stage_map: dict[int, list] = defaultdict(list)
        for el in elements:
            if not isinstance(el, dict):
                continue
            stage = int(el.get("animation_stage", 0))
            stage_map[stage].append(el)

        delay = 0.0
        for stage in sorted(stage_map.keys()):
            stage_delay = delay
            stage_max_added = 0.0
            for el in stage_map[stage]:
                if el.get("shape") in ("arrow", "curved_arrow", "double_arrow", "elbow_arrow"):
                    has_arrow = True
                if el.get("shape") == "double_arrow":
                    has_double_arrow = True
                # All elements in this stage start at the same stage_delay
                svg_str, new_delay = _render_shape({**el}, stage_delay)
                if svg_str:
                    parts.append(svg_str)
                    added = new_delay - stage_delay
                    if added > stage_max_added:
                        stage_max_added = added
            # Advance by the longest shape in this stage + inter-stage gap
            delay += stage_max_added + _STAGE_STEP
    else:
        # Legacy sequential mode
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

