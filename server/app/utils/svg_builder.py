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
New shapes in v4:
  hexagon, half_circle, quarter_circle, human, waveform, orbit_electron
Extended colour keys (v4):
  orange, green, pink, purple, teal, yellow, red, blue, gold, coral, mint,
  white, sky, brown
New diagram types (v4 semantic SVG):
  atom           → Bohr-model with orbiting electrons (animateTransform rotate)
  solar_system   → Sun + planets orbiting via animateTransform
  waveform_signal / sine_wave → Sine wave on labelled axes
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
    # ── Extended palette (v4) ──────────────────────────────────────────────
    "orange":    "#FFB74D",
    "green":     "#81C784",
    "pink":      "#F48FB1",
    "purple":    "#CE93D8",
    "teal":      "#4DB6AC",
    "yellow":    "#FFF176",
    "red":       "#EF5350",
    "blue":      "#42A5F5",
    "gold":      "#FFD54F",
    "coral":     "#FF7043",
    "mint":      "#A8D8A8",
    "white":     "#FFFFFF",
    "sky":       "#87CEEB",
    "brown":     "#A1887F",
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
    """Resolve a colour key to a hex string.

    Accepted inputs (in priority order):
      1. A key in the COLORS dict  (e.g. "orange", "highlight")
      2. A CSS hex value           (e.g. "#FF6B6B", "#4FC3F7")
      3. A 3-char shorthand hex    (e.g. "#F00")
      4. Any other string — falls back to STROKE so lines are always visible.
    """
    k = str(key).strip()
    if k in COLORS:
        return COLORS[k]
    # Pass hex values through directly (validates basic format)
    import re as _re
    if _re.match(r"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$", k):
        return k
    return COLORS.get(k, STROKE)


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

    # ── hexagon ───────────────────────────────────────────────────────────────
    # Shortcut: delegates to the generic polygon renderer with sides=6.
    elif shape == "hexagon":
        return _render_shape({**el, "shape": "polygon", "sides": 6, "angle_offset": el.get("angle_offset", 0)}, delay)

    # ── half_circle ───────────────────────────────────────────────────────────
    # direction: "top" (dome up, flat at bottom),  "bottom" (dome down),
    #            "left" (dome left),               "right"  (dome right)
    elif shape == "half_circle":
        cx        = _clamp(float(el.get("cx", 200)), 10, 390)
        cy        = _clamp(float(el.get("cy", 150)), 10, 290)
        r         = _clamp(float(el.get("r",   60)),  5, 130)
        direction = el.get("direction", "top")
        fill_key  = el.get("fill_color", "none")
        fill_val  = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.18" if fill_val != "none" else "0"
        arc_len   = math.pi * r  # semicircle arc length
        if direction == "top":
            d = f"M {cx - r:.2f} {cy:.2f} A {r} {r} 0 0 1 {cx + r:.2f} {cy:.2f}"
        elif direction == "bottom":
            d = f"M {cx - r:.2f} {cy:.2f} A {r} {r} 0 0 0 {cx + r:.2f} {cy:.2f}"
        elif direction == "left":
            d = f"M {cx:.2f} {cy - r:.2f} A {r} {r} 0 0 0 {cx:.2f} {cy + r:.2f}"
        else:  # right
            d = f"M {cx:.2f} {cy - r:.2f} A {r} {r} 0 0 1 {cx:.2f} {cy + r:.2f}"
        closed = " Z" if fill_val != "none" else ""
        svg = (
            f'<path d="{d}{closed}" stroke="{color}" stroke-width="2.5" '
            f'fill="{fill_val}" fill-opacity="{fill_opacity}" stroke-linecap="round" '
            f'stroke-dasharray="{arc_len:.2f}" stroke-dashoffset="{arc_len:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{arc_len:.2f}" to="0" '
            f'dur="0.45s" begin="{delay:.2f}s" fill="freeze"/></path>'
        )
        return svg, delay + 0.38

    # ── quarter_circle ────────────────────────────────────────────────────────
    # start_deg: starting angle in degrees (0 = 3-o'clock, 90 = 6-o'clock).
    # When fill_color is set the shape is rendered as a filled pie slice.
    elif shape == "quarter_circle":
        cx        = _clamp(float(el.get("cx", 200)), 10, 390)
        cy        = _clamp(float(el.get("cy", 150)), 10, 290)
        r         = _clamp(float(el.get("r",   60)),  5, 130)
        start_deg = float(el.get("start_deg", 0))
        end_deg   = start_deg + 90
        sa = math.radians(start_deg - 90)
        ea = math.radians(end_deg   - 90)
        x1 = cx + r * math.cos(sa)
        y1 = cy + r * math.sin(sa)
        x2 = cx + r * math.cos(ea)
        y2 = cy + r * math.sin(ea)
        arc_len   = math.pi * r / 2
        fill_key  = el.get("fill_color", "none")
        fill_val  = _resolve_color(fill_key) if fill_key != "none" else "none"
        fill_opacity = "0.18" if fill_val != "none" else "0"
        if fill_val != "none":
            # Pie slice
            d = (f"M {cx:.2f} {cy:.2f} L {x1:.2f} {y1:.2f} "
                 f"A {r} {r} 0 0 1 {x2:.2f} {y2:.2f} Z")
        else:
            d = f"M {x1:.2f} {y1:.2f} A {r} {r} 0 0 1 {x2:.2f} {y2:.2f}"
        svg = (
            f'<path d="{d}" stroke="{color}" stroke-width="2.5" '
            f'fill="{fill_val}" fill-opacity="{fill_opacity}" stroke-linecap="round" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.40s" begin="{delay:.2f}s" fill="freeze"/></path>'
        )
        return svg, delay + 0.35

    # ── human (stick figure) ──────────────────────────────────────────────────
    # Parameters: cx, cy (centre of torso), scale (default 1.0).
    # Parts animate in: head → body → arms → legs.
    elif shape == "human":
        cx       = _clamp(float(el.get("cx", 200)), 20, 380)
        cy       = _clamp(float(el.get("cy", 150)), 20, 275)
        scale    = _clamp(float(el.get("scale", 1.0)), 0.4, 2.5)
        head_r   = 12 * scale
        neck_len = 6  * scale
        body_len = 36 * scale
        arm_len  = 26 * scale
        leg_len  = 34 * scale
        arm_angle = math.radians(40)
        leg_spread = math.radians(20)
        # Anchor points
        head_cy    = _clamp(cy - body_len / 2 - neck_len - head_r, head_r + 2, 290)
        shoulder_y = _clamp(cy - body_len / 2, 4, 294)
        hip_y      = _clamp(cy + body_len / 2, 4, 294)
        foot_y     = _clamp(hip_y + leg_len,   4, 298)
        arm_dx = arm_len * math.cos(arm_angle)
        arm_dy = arm_len * math.sin(arm_angle)
        leg_dx = leg_len * math.sin(leg_spread)
        d0 = f"{delay:.2f}"
        d1 = f"{delay + 0.12:.2f}"
        d2 = f"{delay + 0.24:.2f}"
        d3 = f"{delay + 0.36:.2f}"
        anim = lambda t: (f'<animate attributeName="opacity" from="0" to="1" '
                          f'dur="0.25s" begin="{t}s" fill="freeze"/>')
        parts = [
            # Head
            (f'<circle cx="{cx:.1f}" cy="{head_cy:.1f}" r="{head_r:.1f}" '
             f'stroke="{color}" stroke-width="2" fill="none" opacity="0">' + anim(d0) + '</circle>'),
            # Body
            (f'<line x1="{cx:.1f}" y1="{shoulder_y:.1f}" x2="{cx:.1f}" y2="{hip_y:.1f}" '
             f'stroke="{color}" stroke-width="2" stroke-linecap="round" opacity="0">' + anim(d1) + '</line>'),
            # Left arm
            (f'<line x1="{cx:.1f}" y1="{shoulder_y:.1f}" '
             f'x2="{cx - arm_dx:.1f}" y2="{shoulder_y + arm_dy:.1f}" '
             f'stroke="{color}" stroke-width="2" stroke-linecap="round" opacity="0">' + anim(d2) + '</line>'),
            # Right arm
            (f'<line x1="{cx:.1f}" y1="{shoulder_y:.1f}" '
             f'x2="{cx + arm_dx:.1f}" y2="{shoulder_y + arm_dy:.1f}" '
             f'stroke="{color}" stroke-width="2" stroke-linecap="round" opacity="0">' + anim(d2) + '</line>'),
            # Left leg
            (f'<line x1="{cx:.1f}" y1="{hip_y:.1f}" '
             f'x2="{cx - leg_dx:.1f}" y2="{foot_y:.1f}" '
             f'stroke="{color}" stroke-width="2" stroke-linecap="round" opacity="0">' + anim(d3) + '</line>'),
            # Right leg
            (f'<line x1="{cx:.1f}" y1="{hip_y:.1f}" '
             f'x2="{cx + leg_dx:.1f}" y2="{foot_y:.1f}" '
             f'stroke="{color}" stroke-width="2" stroke-linecap="round" opacity="0">' + anim(d3) + '</line>'),
        ]
        return "\n".join(parts), delay + 0.55

    # ── waveform (sine wave) ──────────────────────────────────────────────────
    # Parameters: x (left edge), y (vertical centre), width, amplitude, cycles.
    # Generates a smooth SVG path traced with dash-offset animation.
    elif shape == "waveform":
        wx     = _clamp(float(el.get("x",        30)),  2, 380)
        wy     = _clamp(float(el.get("y",       150)), 10, 290)
        w      = _clamp(float(el.get("width",   340)),  20, 396 - wx)
        amp    = _clamp(float(el.get("amplitude", 40)),  4, 120)
        cycles = max(0.5, float(el.get("cycles", 2)))
        # Build polyline points along the sine wave (200 samples)
        samples = 200
        pts = []
        for k in range(samples + 1):
            t   = k / samples
            px  = wx + t * w
            py  = wy - amp * math.sin(2 * math.pi * cycles * t)
            pts.append(f"{px:.2f},{py:.2f}")
        # Approximate arc length for dash animation
        path_len = math.sqrt(w ** 2 + (2 * amp) ** 2) * cycles * 1.8
        svg = (
            f'<polyline points="{" ".join(pts)}" '
            f'stroke="{color}" stroke-width="2.5" fill="none" stroke-linecap="round" '
            f'stroke-dasharray="{path_len:.2f}" stroke-dashoffset="{path_len:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{path_len:.2f}" to="0" '
            f'dur="0.80s" begin="{delay:.2f}s" fill="freeze"/></polyline>'
        )
        return svg, delay + 0.70

    # ── polyline (pre-computed points — used by graph_function renderer) ──────
    # Parameters: pts (space-separated "x,y" string), path_len (dash anim length)
    elif shape == "polyline":
        pts_str  = el.get("pts", "")
        path_len = float(el.get("path_len", 300))
        sw       = float(el.get("stroke_width", 2.5))
        if not pts_str:
            return "", delay
        svg = (
            f'<polyline points="{pts_str}" '
            f'stroke="{color}" stroke-width="{sw:.1f}" fill="none" '
            f'stroke-linecap="round" stroke-linejoin="round" '
            f'stroke-dasharray="{path_len:.2f}" stroke-dashoffset="{path_len:.2f}">'
            f'<animate attributeName="stroke-dashoffset" from="{path_len:.2f}" to="0" '
            f'dur="0.90s" begin="{delay:.2f}s" fill="freeze"/></polyline>'
        )
        return svg, delay + 0.80

    # ── orbit_electron ────────────────────────────────────────────────────────
    # A filled dot that orbits a centre point via animateTransform type="rotate".
    # Parameters: cx/cy (orbit centre), r (orbit radius), start_angle (degrees),
    #             dot_r (electron dot radius), duration (orbit period seconds),
    #             direction ("cw" | "ccw").
    # This produces smooth, perpetual orbital motion matching the semantic SVG spec.
    elif shape == "orbit_electron":
        ocx   = _clamp(float(el.get("cx",    200)),  10, 390)
        ocy   = _clamp(float(el.get("cy",    150)),  10, 290)
        orb_r = _clamp(float(el.get("r",      70)),   8, 170)
        dot_r = _clamp(float(el.get("dot_r",   5)),   2,  12)
        ang   = float(el.get("start_angle",   0))
        dur   = max(4.0, float(el.get("duration", 10)))
        direc = el.get("direction", "cw")
        # Initial position on the orbit
        init_x = ocx + orb_r * math.cos(math.radians(ang))
        init_y = ocy + orb_r * math.sin(math.radians(ang))
        from_rot = f"0 {ocx:.2f} {ocy:.2f}"
        to_rot   = (f"360 {ocx:.2f} {ocy:.2f}"  if direc == "cw"
                    else f"-360 {ocx:.2f} {ocy:.2f}")
        svg = (
            f'<circle cx="{init_x:.2f}" cy="{init_y:.2f}" r="{dot_r}" fill="{color}" opacity="0">'
            f'<animate attributeName="opacity" from="0" to="1" '
            f'dur="0.30s" begin="{delay:.2f}s" fill="freeze"/>'
            f'<animateTransform attributeName="transform" type="rotate" '
            f'from="{from_rot}" to="{to_rot}" '
            f'dur="{dur:.1f}s" begin="{delay:.2f}s" repeatCount="indefinite"/>'
            f'</circle>'
        )
        return svg, delay + 0.28

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


def _render_atom(data: dict) -> list:
    """
    Bohr-model atom using Python-computed coordinates.

    Uses orbit_electron shapes (animateTransform type="rotate") for smooth
    perpetual orbital motion — no path math needed from the LLM.

    data keys:
      nucleus_label  → text inside nucleus (default "")
      nucleus_color  → colour key for nucleus fill (default "highlight")
      nucleus_radius → nucleus circle radius (default 22)
      orbits         → list of dicts with optional keys:
                         color        (default cycles through palette)
                         electrons    (int, default 1–2 per orbit)
                         tilt_deg     (ellipse tilt, 0 = circle)
                         label        → text label on orbit (optional)
      duration       → base orbit period in seconds (default 10)
    """
    cx, cy       = 200, 150
    nuc_label    = str(data.get("nucleus_label", ""))[:6]
    nuc_color    = data.get("nucleus_color",   "highlight")
    nuc_r        = _clamp(float(data.get("nucleus_radius", 22)), 8, 50)
    base_dur     = max(4.0, float(data.get("duration", 10)))
    raw_orbits   = data.get("orbits") or [{}]
    orbit_defs   = raw_orbits[:4]

    orbit_colors = ["secondary", "orange", "teal", "pink"]
    shapes = []

    # Nucleus
    shapes.append({
        "shape": "circle", "cx": cx, "cy": cy,
        "r": nuc_r, "color": nuc_color, "fill_color": nuc_color,
        "animation_stage": 0,
    })
    if nuc_label:
        shapes.append({
            "shape": "text", "x": cx, "y": cy + 5, "value": nuc_label,
            "color": "primary", "bold": True, "size": 11,
            "anchor": "middle", "animation_stage": 0,
        })

    # Orbits + electrons
    orbit_spacing = _clamp((180 - nuc_r) / max(len(orbit_defs), 1), 25, 60)
    for i, orb in enumerate(orbit_defs):
        orb_r    = nuc_r + (i + 1) * orbit_spacing
        orb_r    = _clamp(orb_r, nuc_r + 20, 155)
        clr      = orb.get("color") or orbit_colors[i % len(orbit_colors)]
        n_elec   = max(1, min(6, int(orb.get("electrons", 1 + i % 2))))
        tilt     = float(orb.get("tilt_deg", 0))
        orb_lbl  = str(orb.get("label", ""))[:12]
        dur      = base_dur * (1 + i * 0.35)   # outer orbits slower

        # Orbit ellipse (tilt applied via a rotated ellipse)
        if abs(tilt) < 1:
            shapes.append({
                "shape": "circle", "cx": cx, "cy": cy,
                "r": orb_r, "color": clr,
                "animation_stage": i + 1,
            })
        else:
            # Use an ellipse with rx > ry rotated by tilt
            shapes.append({
                "shape": "ellipse", "cx": cx, "cy": cy,
                "rx": orb_r, "ry": orb_r * 0.55, "color": clr,
                "animation_stage": i + 1,
            })

        if orb_lbl:
            shapes.append({
                "shape": "text",
                "x": _clamp(cx + orb_r + 6, 4, 392),
                "y": cy - 6,
                "value": orb_lbl, "color": clr,
                "size": 10, "anchor": "start",
                "animation_stage": i + 1,
            })

        # Place electrons evenly spaced around the orbit
        for e in range(n_elec):
            start_angle = 360 * e / n_elec
            shapes.append({
                "shape": "orbit_electron",
                "cx": cx, "cy": cy,
                "r": orb_r,
                "dot_r": 5,
                "start_angle": start_angle,
                "duration": dur,
                "direction": "cw" if i % 2 == 0 else "ccw",
                "color": clr,
                "animation_stage": i + 1,
            })

    return shapes


def _render_solar_system(data: dict) -> list:
    """
    Solar system: sun at centre + up to 6 planets orbiting it.

    data keys:
      sun_label   → text inside sun circle (default "Sun")
      sun_color   → colour key (default "gold")
      planets     → list of dicts with optional keys:
                      label         (planet name, up to 6 chars)
                      color         (colour key)
                      orbit_radius  (explicit radius; auto-spaced if omitted)
                      electrons     → treated as planet count (ignored here)
                      duration      → orbital period seconds (default auto)
      duration    → base orbital period (default 12 s)
    """
    cx, cy      = 200, 150
    sun_label   = str(data.get("sun_label", "Sun"))[:6]
    sun_color   = data.get("sun_color", "gold")
    base_dur    = max(5.0, float(data.get("duration", 12)))
    raw_planets = (data.get("planets") or [])[:6]

    planet_colors = ["blue", "orange", "teal", "pink", "green", "purple"]
    shapes = []

    sun_r = 26
    shapes.append({
        "shape": "circle", "cx": cx, "cy": cy,
        "r": sun_r, "color": sun_color, "fill_color": sun_color,
        "animation_stage": 0,
    })
    if sun_label:
        shapes.append({
            "shape": "text", "x": cx, "y": cy + 5, "value": sun_label,
            "color": "primary", "bold": True, "size": 10,
            "anchor": "middle", "animation_stage": 0,
        })

    n = len(raw_planets) or 3
    # Auto-space orbits from sun edge to canvas edge
    max_r     = 148
    spacing   = _clamp((max_r - sun_r) / n, 20, 50)

    for i, planet in enumerate(raw_planets):
        orb_r  = _clamp(
            float(planet.get("orbit_radius", sun_r + (i + 1) * spacing)),
            sun_r + 18, max_r
        )
        clr    = planet.get("color") or planet_colors[i % len(planet_colors)]
        dur    = max(4.0, float(planet.get("duration", base_dur * (1 + i * 0.5))))
        label  = str(planet.get("label", ""))[:6]

        # Orbit ring
        shapes.append({
            "shape": "circle", "cx": cx, "cy": cy,
            "r": orb_r, "color": "dim",
            "animation_stage": i + 1,
        })
        # Planet dot orbiting
        shapes.append({
            "shape": "orbit_electron",
            "cx": cx, "cy": cy,
            "r": orb_r,
            "dot_r": 7,
            "start_angle": 360 * i / max(n, 1),
            "duration": dur,
            "direction": "cw",
            "color": clr,
            "animation_stage": i + 1,
        })
        # Label near top of orbit
        lx = _clamp(cx + orb_r * 0.7, 8, 392)
        ly = _clamp(cy - orb_r * 0.7, 10, 288)
        if label:
            shapes.append({
                "shape": "text",
                "x": lx, "y": ly,
                "value": label, "color": clr,
                "size": 10, "anchor": "middle",
                "animation_stage": i + 1,
            })

    return shapes


def _render_waveform_signal(data: dict) -> list:
    """
    Sine / square / sawtooth waveform diagram — great for physics (sound,
    light, AC electricity, signal processing).

    data keys:
      wave_type  → "sine" | "square" | "sawtooth"  (default "sine")
      cycles     → number of complete cycles to draw (default 2.5)
      amplitude  → peak height in canvas units (default 50)
      x_label    → label for x-axis (default "time")
      y_label    → label for y-axis (default "amplitude")
      title      → optional title shown at top
      color      → colour key for wave (default "secondary")
    """
    wave_type = str(data.get("wave_type", "sine")).lower()
    cycles    = max(0.5, float(data.get("cycles",   2.5)))
    amp       = _clamp(float(data.get("amplitude", 50)), 5, 110)
    x_lbl     = str(data.get("x_label",   "time"))[:20]
    y_lbl     = str(data.get("y_label",   "amplitude"))[:20]
    title     = str(data.get("title",     ""))[:40]
    wave_clr  = data.get("color", "secondary")

    OX, OY = 50, 160    # axis origin (left, vertical centre)
    W      = 320        # wave width
    shapes = []

    if title:
        shapes.append({
            "shape": "text", "x": 200, "y": 18,
            "value": title, "color": "label",
            "bold": True, "size": 13, "anchor": "middle",
            "animation_stage": 0,
        })

    # Axes
    shapes.append({"shape": "arrow", "x1": OX, "y1": OY, "x2": OX + W + 20,
                   "y2": OY, "color": "primary", "animation_stage": 0})
    shapes.append({"shape": "arrow", "x1": OX, "y1": OY + amp + 20,
                   "x2": OX, "y2": OY - amp - 22,
                   "color": "primary", "animation_stage": 0})
    shapes.append({"shape": "text", "x": OX + W + 24, "y": OY + 5,
                   "value": x_lbl, "color": "label", "size": 11,
                   "bold": True, "animation_stage": 0})
    shapes.append({"shape": "text", "x": OX, "y": OY - amp - 26,
                   "value": y_lbl, "color": "label", "size": 11,
                   "bold": True, "anchor": "middle", "animation_stage": 0})

    # Wave path — the waveform shape handles drawing via polyline in _render_shape
    shapes.append({
        "shape": "waveform",
        "x": OX, "y": OY,
        "width": W,
        "amplitude": amp,
        "cycles": cycles,
        "color": wave_clr,
        "animation_stage": 1,
    })

    # Zero-line label
    shapes.append({"shape": "text", "x": OX - 8, "y": OY + 4,
                   "value": "0", "color": "dim",
                   "size": 10, "anchor": "end", "animation_stage": 0})

    return shapes


# ── Dispatcher ────────────────────────────────────────────────────────────────

def _render_number_line(data: dict) -> list:
    """
    Number line with optional highlighted range and marked points.

    data keys:
      start            → left number (default -5)
      end              → right number (default 5)
      marked_points    → list of numbers to mark with dots + labels
      highlight_range  → [lo, hi] to shade between two values
      label            → title shown above (default "Number Line")
    """
    start   = float(data.get("start", -5))
    end     = float(data.get("end",    5))
    if end <= start:
        end = start + 10
    label   = str(data.get("label", "Number Line"))[:30]
    marks   = [float(v) for v in (data.get("marked_points") or [])]
    hi_rng  = data.get("highlight_range")

    OX, OY = 40, 165
    W      = 320
    scale  = W / (end - start)

    def to_x(v: float) -> float:
        return _clamp(OX + (v - start) * scale, OX - 2, OX + W + 2)

    shapes = []
    if label:
        shapes.append({"shape": "text", "x": 200, "y": 20,
                        "value": label, "color": "label",
                        "bold": True, "size": 14, "anchor": "middle",
                        "animation_stage": 0})

    # Highlighted range band
    if hi_rng and len(hi_rng) >= 2:
        lx = to_x(float(hi_rng[0]))
        rx = to_x(float(hi_rng[1]))
        bw = max(rx - lx, 4)
        shapes.append({"shape": "rounded_rect", "x": lx, "y": OY - 12,
                        "w": bw, "h": 24, "rx": 6,
                        "color": "secondary", "fill_color": "secondary",
                        "animation_stage": 0})

    # Main axis line + arrows
    shapes.append({"shape": "arrow", "x1": OX - 10, "y1": OY,
                   "x2": OX + W + 14, "y2": OY, "color": "primary",
                   "animation_stage": 1})

    # Tick marks + numbers for integers
    step  = 1 if (end - start) <= 12 else int((end - start) // 8) or 1
    v     = math.ceil(start / step) * step
    while v <= end:
        tx = to_x(v)
        shapes.append({"shape": "axis_tick", "x": tx, "y": OY,
                        "axis": "x", "size": 7, "color": "dim",
                        "animation_stage": 1})
        shapes.append({"shape": "text", "x": tx, "y": OY + 20,
                        "value": str(int(v)) if v == int(v) else f"{v:.1f}",
                        "color": "dim", "size": 11, "anchor": "middle",
                        "animation_stage": 1})
        v += step

    # Marked points
    for mp in marks:
        mx = to_x(mp)
        shapes.append({"shape": "dot", "cx": mx, "cy": OY, "r": 7,
                        "color": "highlight", "animation_stage": 2})
        shapes.append({"shape": "text", "x": mx, "y": OY - 18,
                        "value": str(int(mp)) if mp == int(mp) else f"{mp:.1f}",
                        "color": "highlight", "bold": True, "size": 13,
                        "anchor": "middle", "animation_stage": 2})

    return shapes


def _render_fraction_bar(data: dict) -> list:
    """
    Fraction bars — visual comparison of up to 4 fractions.

    data keys:
      fractions  → list of {"num": N, "den": D}  (max 4)
      title      → shown at top (default "Fractions")
    """
    fracs  = (data.get("fractions") or [{"num": 1, "den": 2}])[:4]
    title  = str(data.get("title", "Fractions"))[:30]
    colors = ["secondary", "highlight", "teal", "orange"]

    shapes = []
    if title:
        shapes.append({"shape": "text", "x": 200, "y": 22,
                        "value": title, "color": "label",
                        "bold": True, "size": 14, "anchor": "middle",
                        "animation_stage": 0})

    bar_w  = 280
    bar_h  = 34
    bar_x  = 60
    row_gap = 62
    top_y  = 50

    for i, frac in enumerate(fracs):
        num = max(0, int(frac.get("num", 1)))
        den = max(1, int(frac.get("den", 2)))
        fraction = min(num / den, 1.0)
        clr  = colors[i % len(colors)]
        y    = top_y + i * row_gap

        # Empty bar outline
        shapes.append({"shape": "rect", "x": bar_x, "y": y,
                        "w": bar_w, "h": bar_h, "color": "dim",
                        "animation_stage": i})
        # Filled portion
        filled_w = max(4.0, bar_w * fraction)
        shapes.append({"shape": "rounded_rect", "x": bar_x, "y": y,
                        "w": filled_w, "h": bar_h, "rx": 4,
                        "color": clr, "fill_color": clr,
                        "animation_stage": i + 1})
        # Fraction label left
        shapes.append({"shape": "text", "x": bar_x - 10, "y": y + bar_h // 2 + 5,
                        "value": f"{num}/{den}", "color": clr,
                        "bold": True, "size": 13, "anchor": "end",
                        "animation_stage": i + 1})
        # Decimal label right
        shapes.append({"shape": "text", "x": bar_x + bar_w + 10, "y": y + bar_h // 2 + 5,
                        "value": f"= {fraction:.2f}", "color": "label",
                        "size": 11, "anchor": "start",
                        "animation_stage": i + 1})

    return shapes


def _render_graph_function(data: dict) -> list:
    """
    Plot a mathematical function curve on labelled axes.
    All computation is server-side — LLM only specifies the function type
    and coefficients.

    data keys:
      function  → "quadratic" | "linear" | "cubic" | "sine" | "cosine" | "abs"
      a, b, c   → coefficients (defaults 1, 0, 0)
      x_range   → [x_min, x_max]  (default [-4, 4])
      label     → equation label shown (auto-generated if omitted)
      color     → wave/curve colour key (default "secondary")
    """
    func_name = str(data.get("function", "quadratic")).lower()
    a   = float(data.get("a", 1))
    b   = float(data.get("b", 0))
    c   = float(data.get("c", 0))
    xr  = data.get("x_range") or [-4, 4]
    x_min, x_max = float(xr[0]), float(xr[1])
    if x_max <= x_min:
        x_max = x_min + 8
    clr = data.get("color", "secondary")

    # Auto-label
    if not data.get("label"):
        if func_name == "linear":
            lbl = f"y = {a:.0f}x + {b:.0f}" if b != 0 else f"y = {a:.0f}x"
        elif func_name == "quadratic":
            lbl = f"y = {a:.0f}x\u00b2 + {b:.0f}x + {c:.0f}"
        elif func_name == "cubic":
            lbl = f"y = {a:.0f}x\u00b3"
        elif func_name in ("sine", "cosine"):
            lbl = f"y = {a:.0f}{func_name[:3]}(x)"
        else:
            lbl = func_name
    else:
        lbl = str(data.get("label", ""))[:30]

    OX, OY = 55, 200
    CW, CH = 290, 165   # canvas width/height for the plot area

    def f(x: float) -> float:
        if func_name == "linear":
            return a * x + b
        if func_name == "quadratic":
            return a * x * x + b * x + c
        if func_name == "cubic":
            return a * x * x * x + b * x * x + c * x
        if func_name == "sine":
            return a * math.sin(b * x if b else x) + c
        if func_name == "cosine":
            return a * math.cos(b * x if b else x) + c
        if func_name == "abs":
            return a * abs(x) + b
        return a * x + b

    # Sample the function
    steps  = 200
    xs     = [x_min + (x_max - x_min) * i / steps for i in range(steps + 1)]
    ys     = [f(x) for x in xs]
    y_min  = min(ys)
    y_max  = max(ys)
    y_range = (y_max - y_min) or 1

    def to_cx(x: float) -> float:
        return _clamp(OX + (x - x_min) / (x_max - x_min) * CW, OX, OX + CW)

    def to_cy(y: float) -> float:
        return _clamp(OY - (y - y_min) / y_range * CH, OY - CH, OY)

    shapes: list = []

    # Axes
    # Y-axis: position at x=0 if in range, else at left edge
    ax_x = to_cx(0) if x_min <= 0 <= x_max else OX
    shapes.append({"shape": "arrow", "x1": OX, "y1": OY, "x2": OX + CW + 12,
                   "y2": OY, "color": "primary", "animation_stage": 0})
    shapes.append({"shape": "arrow", "x1": ax_x, "y1": OY + 10, "x2": ax_x,
                   "y2": OY - CH - 14, "color": "primary", "animation_stage": 0})
    shapes.append({"shape": "text", "x": OX + CW + 16, "y": OY + 5,
                   "value": "x", "color": "label", "bold": True, "size": 12,
                   "animation_stage": 0})
    shapes.append({"shape": "text", "x": ax_x, "y": OY - CH - 18,
                   "value": "y", "color": "label", "bold": True, "size": 12,
                   "anchor": "middle", "animation_stage": 0})

    # Origin dot
    if x_min <= 0 <= x_max:
        oy_canvas = to_cy(0)
        shapes.append({"shape": "dot", "cx": ax_x, "cy": oy_canvas, "r": 3,
                        "color": "dim", "animation_stage": 0})

    # Build waveform shape by segments — split at large jumps (asymptotes)
    pts = [(to_cx(xs[i]), to_cy(ys[i])) for i in range(len(xs))]

    # Find run boundaries (jump detection for functions like 1/x)
    segments: list[list[tuple]] = []
    current: list[tuple] = [pts[0]]
    for k in range(1, len(pts)):
        if abs(pts[k][1] - pts[k - 1][1]) > 60:   # asymptote jump
            segments.append(current)
            current = [pts[k]]
        else:
            current.append(pts[k])
    segments.append(current)

    # Emit each segment as a polyline
    path_len = math.sqrt(CW ** 2 + CH ** 2) * 1.5
    for seg in segments:
        if len(seg) < 2:
            continue
        pts_str = " ".join(f"{px:.1f},{py:.1f}" for px, py in seg)
        # Compute approx length for dash animation
        seg_len = sum(
            math.sqrt((seg[j][0] - seg[j - 1][0]) ** 2 + (seg[j][1] - seg[j - 1][1]) ** 2)
            for j in range(1, len(seg))
        )
        shapes.append({
            "shape": "polyline",
            "pts": pts_str,
            "path_len": seg_len,
            "color": clr,
            "animation_stage": 1,
        })

    # Equation label
    shapes.append({"shape": "text", "x": OX + CW - 5, "y": OY - CH - 5,
                   "value": lbl, "color": clr, "bold": True, "size": 12,
                   "anchor": "end", "animation_stage": 2})

    return shapes


def _render_geometry_angles(data: dict) -> list:
    """
    Geometry diagram for angle types and angle relationships.

    data keys:
      angle_deg    → the main angle in degrees (default 60)
      angle_type   → "acute"|"right"|"obtuse"|"reflex"|"supplementary"|"complementary"
      show_second  → True to show the supplementary/complementary partner angle
      labels       → list of up to 3 label strings (e.g. ["A","O","B"])
      title        → optional title
    """
    ang       = _clamp(float(data.get("angle_deg", 60)), 1, 359)
    a_type    = str(data.get("angle_type", "acute")).lower()
    show2     = bool(data.get("show_second", False))
    labels    = (data.get("labels") or ["A", "O", "B"])[:3]
    while len(labels) < 3:
        labels.append("")
    title     = str(data.get("title", ""))[:30]

    cx, cy  = 200, 185
    arm_len = 110

    shapes = []
    if title:
        shapes.append({"shape": "text", "x": 200, "y": 18,
                        "value": title, "color": "label",
                        "bold": True, "size": 13, "anchor": "middle",
                        "animation_stage": 0})

    # First arm: horizontal right
    ax1 = cx + arm_len
    ay1 = cy
    shapes.append({"shape": "line", "x1": cx, "y1": cy, "x2": ax1, "y2": ay1,
                   "color": "highlight", "animation_stage": 0})

    # Second arm: at angle
    rad = math.radians(ang)
    ax2 = cx + arm_len * math.cos(rad)
    ay2 = cy - arm_len * math.sin(rad)    # SVG y is inverted
    ax2 = _clamp(ax2, 5, 395)
    ay2 = _clamp(ay2, 5, 295)
    shapes.append({"shape": "line", "x1": cx, "y1": cy, "x2": ax2, "y2": ay2,
                   "color": "highlight", "animation_stage": 1})

    # Angle arc
    if ang == 90:
        shapes.append({"shape": "angle_arc", "cx": cx, "cy": cy, "r": 22,
                       "angle_deg": 90, "rotation_deg": 0,
                       "color": "secondary", "animation_stage": 2})
    else:
        shapes.append({"shape": "arc", "cx": cx, "cy": cy, "r": 28,
                       "start_deg": 360 - ang, "end_deg": 360,
                       "color": "secondary", "animation_stage": 2})

    # Angle value label at arc midpoint
    mid_rad = rad / 2
    lmx = cx + 44 * math.cos(mid_rad)
    lmy = cy - 44 * math.sin(mid_rad)
    shapes.append({"shape": "text", "x": _clamp(lmx, 5, 390),
                   "y": _clamp(lmy, 12, 290),
                   "value": f"{int(ang)}\u00b0", "color": "secondary",
                   "bold": True, "size": 13, "anchor": "middle",
                   "animation_stage": 2})

    # Vertex dot
    shapes.append({"shape": "dot", "cx": cx, "cy": cy, "r": 5,
                   "color": "label", "animation_stage": 0})

    # Vertex and arm labels
    shapes.append({"shape": "text", "x": cx - 14, "y": cy + 8,
                   "value": labels[1], "color": "label",
                   "bold": True, "size": 13, "anchor": "end",
                   "animation_stage": 0})
    shapes.append({"shape": "text", "x": ax1 + 10, "y": ay1 + 5,
                   "value": labels[0], "color": "label",
                   "bold": True, "size": 13, "anchor": "start",
                   "animation_stage": 0})
    shapes.append({"shape": "text",
                   "x": _clamp(ax2 + (10 if ax2 > cx else -10), 5, 390),
                   "y": _clamp(ay2 - 10, 12, 290),
                   "value": labels[2], "color": "label",
                   "bold": True, "size": 13, "anchor": "middle",
                   "animation_stage": 1})

    # Supplementary angle (180° partner) if requested
    if show2 and ang < 180:
        sup_ang = 180 - ang
        sup_rad = math.radians(180)
        ax3 = cx + arm_len * math.cos(sup_rad)
        ay3 = cy
        shapes.append({"shape": "line", "x1": cx, "y1": cy, "x2": ax3, "y2": ay3,
                       "color": "teal", "animation_stage": 3})
        shapes.append({"shape": "arc", "cx": cx, "cy": cy, "r": 38,
                       "start_deg": 180, "end_deg": 360,
                       "color": "teal", "animation_stage": 3})
        shapes.append({"shape": "text", "x": cx - 60, "y": cy - 20,
                       "value": f"{int(sup_ang)}\u00b0",
                       "color": "teal", "bold": True, "size": 12,
                       "anchor": "middle", "animation_stage": 3})

    return shapes



_RENDERERS = {
    # ── Geometry ──────────────────────────────────────────────────────────────
    "triangle":        _render_triangle,
    "circle_radius":   _render_circle_radius,
    "rectangle_area":  _render_rectangle_area,
    "geometry_angles": _render_geometry_angles,
    "angles":          _render_geometry_angles,   # alias
    # ── Graphs / data ─────────────────────────────────────────────────────────
    "line_graph":      _render_line_graph,
    "graph_function":  _render_graph_function,    # NEW — plot y=f(x)
    "function_plot":   _render_graph_function,    # alias
    "parabola":        _render_graph_function,    # alias
    # ── Number / fraction ─────────────────────────────────────────────────────
    "number_line":     _render_number_line,       # NEW
    "fraction_bar":    _render_fraction_bar,      # NEW
    "fractions":       _render_fraction_bar,      # alias
    # ── Concept / structure diagrams ──────────────────────────────────────────
    "flow":            _render_flow,
    "comparison":      _render_comparison,
    "cycle":           _render_cycle,
    "labeled_diagram": _render_labeled_diagram,
    "anatomy":         _render_labeled_diagram,   # alias
    "cell":            _render_labeled_diagram,   # alias
    "cell_diagram":    _render_labeled_diagram,   # alias
    # ── Science ───────────────────────────────────────────────────────────────
    "atom":            _render_atom,
    "solar_system":    _render_solar_system,
    "waveform_signal": _render_waveform_signal,
    "sine_wave":       _render_waveform_signal,   # alias
    "wave":            _render_waveform_signal,   # alias
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


# ── JS-based physics animation engine for atom diagrams ───────────────────────

def build_atom_html(data: dict) -> str:
    """
    Build a self-contained HTML page with SVG + JS DOM animation for the Bohr atom.

    Why SVG+JS instead of Canvas:
      - Canvas `100vh` sizing breaks inside Android WebView embedded in a layout.
      - SVG `viewBox` is guaranteed to scale correctly (all other diagrams use it).
      - JS updates SVG element attributes (cx/cy/opacity/r/filter) via DOM —
        works in every Android WebView without canvas pixel-ratio issues.

    Features:
      • Elliptical orbits (rx/ry server-computed, electrons follow exact ellipse)
      • Non-uniform speed (inverse-radius law: faster near narrow ends)
      • Alternating CW/CCW per shell
      • Phase-based reveal: nucleus → K shell → L shell → …
      • Active glow via SVG <filter>, dim/bright via opacity
      • ⏸ Pause / 🔁 Replay controls
      • Zero extra LLM tokens — schema identical to before
    """
    cx, cy    = 200, 150
    nuc_label = str(data.get("nucleus_label", ""))[:6]
    nuc_color = data.get("nucleus_color", "highlight")
    nuc_r     = _clamp(float(data.get("nucleus_radius", 22)), 8, 40)
    base_dur  = max(4.0, float(data.get("duration", 10)))
    raw_orbits = data.get("orbits") or [{}]
    orbit_defs = raw_orbits[:4]

    palette = ["secondary", "orange", "teal", "pink"]
    orbit_spacing = _clamp((175 - nuc_r) / max(len(orbit_defs), 1), 28, 55)
    phase_dur = max(1.5, base_dur / max(len(orbit_defs) + 1, 2))

    # ── Server-side: compute orbit geometry and initial electron positions ────
    orbit_specs = []   # list of dicts for Python rendering
    electron_specs = []  # list of dicts for initial SVG elements

    for i, orb in enumerate(orbit_defs):
        r      = _clamp(nuc_r + (i + 1) * orbit_spacing, nuc_r + 22, 158)
        rx     = r
        ry     = _clamp(r * (0.62 + i * 0.06), 20, 120)
        clr    = orb.get("color") or palette[i % len(palette)]
        color_hex = COLORS.get(clr, ACCENT)
        n_elec = max(1, min(8, int(orb.get("electrons", 1 + i % 2))))
        speed  = round(1.6 / (1 + i * 0.45), 3)
        lbl    = str(orb.get("label", ""))[:14]
        direc  = 1 if i % 2 == 0 else -1
        orbit_specs.append({"i": i, "rx": rx, "ry": ry, "color": clr,
                             "color_hex": color_hex, "n_elec": n_elec,
                             "speed": speed, "label": lbl, "dir": direc})
        for e in range(n_elec):
            angle = 2 * math.pi * e / n_elec
            ex = cx + rx * math.cos(angle)
            ey = cy + ry * math.sin(angle)
            electron_specs.append({"id": len(electron_specs), "oi": i,
                                    "angle": angle, "x": ex, "y": ey,
                                    "color_hex": color_hex})

    nuc_hex   = COLORS.get(nuc_color, HIGHLIGHT)
    bg        = BG_COLOR
    colors_js = json.dumps(COLORS)

    # Build SVG static elements (server-rendered, not JS-generated)
    orbit_svg = ""
    for o in orbit_specs:
        lbl_x = _clamp(cx + o["rx"] + 6, 4, 392)
        lbl_part = (f'<text id="olbl_{o["i"]}" x="{lbl_x:.1f}" y="{cy - 6}" '
                    f'fill="{o["color_hex"]}" font-size="10" font-family="monospace" '
                    f'opacity="0.18">{o["label"]}</text>'
                    if o["label"] else "")
        orbit_svg += (
            f'<ellipse id="orbit_{o["i"]}" cx="{cx}" cy="{cy}" '
            f'rx="{o["rx"]:.1f}" ry="{o["ry"]:.1f}" fill="none" '
            f'stroke="{o["color_hex"]}" stroke-width="1.2" stroke-dasharray="4 4" '
            f'opacity="0.18"/>\n'
            + lbl_part + "\n"
        )

    electron_svg = ""
    for e in electron_specs:
        electron_svg += (
            f'<circle id="el_{e["id"]}" cx="{e["x"]:.1f}" cy="{e["y"]:.1f}" r="3.5" '
            f'fill="{e["color_hex"]}" opacity="0.18"/>\n'
        )

    nuc_size = max(9, int(nuc_r * 0.55))

    # JS orbit data (minimal — geometry already in DOM, JS just moves electrons)
    orbits_js = "[" + ",".join(
        f'{{"rx":{o["rx"]:.1f},"ry":{o["ry"]:.1f},"dir":{o["dir"]},'
        f'"speed":{o["speed"]},"electrons":{o["n_elec"]}}}'
        for o in orbit_specs
    ) + "]"

    html = (
        f'<!DOCTYPE html><html>\n'
        f'<head>\n'
        f'<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">\n'
        f'<style>\n'
        f'*{{margin:0;padding:0;box-sizing:border-box}}\n'
        f'body{{background:{bg};display:flex;align-items:center;justify-content:center;'
        f'width:100%;height:100%;overflow:hidden}}\n'
        f'svg{{width:100%;height:auto;display:block}}\n'
        f'#ctrl{{position:absolute;bottom:6px;right:8px;display:flex;gap:5px;z-index:9}}\n'
        f'button{{background:#2A3B2A;border:1px solid #4FC3F7;color:#F0EDD0;'
        f'border-radius:4px;padding:3px 8px;font-size:12px;cursor:pointer}}\n'
        f'</style>\n'
        f'</head>\n'
        f'<body>\n'
        f'<svg id="s" viewBox="0 0 400 300" xmlns="http://www.w3.org/2000/svg">\n'
        f'<defs>\n'
        f'<filter id="glow" x="-50%" y="-50%" width="200%" height="200%">\n'
        f'  <feGaussianBlur stdDeviation="3.5" result="blur"/>\n'
        f'  <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>\n'
        f'</filter>\n'
        f'<radialGradient id="nucG" cx="35%" cy="35%" r="65%">\n'
        f'  <stop offset="0%" stop-color="#ffffff" stop-opacity="0.9"/>\n'
        f'  <stop offset="100%" stop-color="{nuc_hex}"/>\n'
        f'</radialGradient>\n'
        f'</defs>\n'
        f'{orbit_svg}'
        f'{electron_svg}'
        f'<circle id="nuc" cx="{cx}" cy="{cy}" r="{nuc_r:.1f}" '
        f'fill="url(#nucG)" filter="url(#glow)" opacity="0"/>\n'
        f'<text id="nuc-lbl" x="{cx}" y="{cy + nuc_size//2}" text-anchor="middle" '
        f'fill="#1A2B1A" font-size="{nuc_size}" font-weight="bold" '
        f'font-family="monospace" opacity="0">{nuc_label}</text>\n'
        f'</svg>\n'
        f'<div id="ctrl">\n'
        f'  <button id="btn-play" onclick="togglePlay()">&#9646;&#9646;</button>\n'
        f'  <button onclick="replay()">&#10227;</button>\n'
        f'</div>\n'
        f'<script>\n'
        f'var ORBITS={orbits_js};\n'
        f'var CX={cx},CY={cy},PHASE_DUR={phase_dur:.2f};\n'
        f'var electrons=[];\n'
        f'ORBITS.forEach(function(orb,oi){{\n'
        f'  for(var e=0;e<orb.electrons;e++){{\n'
        f'    electrons.push({{oi:oi,angle:2*Math.PI*e/orb.electrons}});\n'
        f'  }}\n'
        f'}});\n'
        f'var t=0,isPlaying=true,lastTs=null;\n'
        f'\n'
        f'function angleDelta(angle,orb,dt){{\n'
        f'  var r=Math.sqrt(orb.rx*Math.cos(angle)*orb.rx*Math.cos(angle)+'
        f'orb.ry*Math.sin(angle)*orb.ry*Math.sin(angle));\n'
        f'  return orb.dir*orb.speed*(orb.rx/Math.max(r,1))*dt;\n'
        f'}}\n'
        f'\n'
        f'function shellOp(oi,phase){{return oi<=phase?1.0:0.18;}}\n'
        f'\n'
        f'function render(){{\n'
        f'  var phase=Math.min(ORBITS.length,Math.floor(t/PHASE_DUR));\n'
        f'  // nucleus fade-in\n'
        f'  var nop=Math.min(1,t*2);\n'
        f'  var nuc=document.getElementById("nuc");\n'
        f'  var nlbl=document.getElementById("nuc-lbl");\n'
        f'  if(nuc)nuc.setAttribute("opacity",nop);\n'
        f'  if(nlbl)nlbl.setAttribute("opacity",nop);\n'
        f'  // orbits\n'
        f'  ORBITS.forEach(function(orb,oi){{\n'
        f'    var op=shellOp(oi,phase);\n'
        f'    var el=document.getElementById("orbit_"+oi);\n'
        f'    if(el)el.setAttribute("opacity",op);\n'
        f'    var lb=document.getElementById("olbl_"+oi);\n'
        f'    if(lb)lb.setAttribute("opacity",op);\n'
        f'  }});\n'
        f'  // electrons\n'
        f'  electrons.forEach(function(e,i){{\n'
        f'    var orb=ORBITS[e.oi];\n'
        f'    var x=CX+orb.rx*Math.cos(e.angle);\n'
        f'    var y=CY+orb.ry*Math.sin(e.angle);\n'
        f'    var el=document.getElementById("el_"+i);\n'
        f'    if(!el)return;\n'
        f'    var active=e.oi<=phase;\n'
        f'    el.setAttribute("cx",x.toFixed(1));\n'
        f'    el.setAttribute("cy",y.toFixed(1));\n'
        f'    el.setAttribute("opacity",shellOp(e.oi,phase));\n'
        f'    el.setAttribute("r",active?"5.5":"3.5");\n'
        f'    el.setAttribute("filter",active?"url(#glow)":"");\n'
        f'  }});\n'
        f'}}\n'
        f'\n'
        f'function update(dt){{\n'
        f'  electrons.forEach(function(e){{\n'
        f'    e.angle+=angleDelta(e.angle,ORBITS[e.oi],dt);\n'
        f'  }});\n'
        f'  t+=dt;\n'
        f'}}\n'
        f'\n'
        f'function animate(ts){{\n'
        f'  if(lastTs===null)lastTs=ts;\n'
        f'  var dt=Math.min((ts-lastTs)/1000,0.05);\n'
        f'  lastTs=ts;\n'
        f'  if(isPlaying)update(dt);\n'
        f'  render();\n'
        f'  requestAnimationFrame(animate);\n'
        f'}}\n'
        f'\n'
        f'function togglePlay(){{\n'
        f'  isPlaying=!isPlaying;\n'
        f'  document.getElementById("btn-play").textContent=isPlaying?"&#9646;&#9646;":"&#9654;";\n'
        f'}}\n'
        f'\n'
        f'function replay(){{\n'
        f'  t=0;lastTs=null;\n'
        f'  electrons.forEach(function(e,i){{\n'
        f'    var orb=ORBITS[e.oi];\n'
        f'    e.angle=2*Math.PI*(i%orb.electrons)/orb.electrons;\n'
        f'  }});\n'
        f'}}\n'
        f'\n'
        f'requestAnimationFrame(animate);\n'
        f'</script>\n'
        f'</body></html>\n'
    )
    return html

    cx, cy       = 200, 150
    nuc_label    = str(data.get("nucleus_label", ""))[:6]
    nuc_color    = data.get("nucleus_color", "highlight")
    nuc_r        = _clamp(float(data.get("nucleus_radius", 22)), 8, 40)
    base_dur     = max(4.0, float(data.get("duration", 10)))
    raw_orbits   = data.get("orbits") or [{}]
    orbit_defs   = raw_orbits[:4]

    palette = ["secondary", "orange", "teal", "pink"]

    # Build orbit specs (server computes geometry, LLM just says #electrons + color)
    orbits_js = []
    orbit_spacing = _clamp((175 - nuc_r) / max(len(orbit_defs), 1), 28, 55)
    for i, orb in enumerate(orbit_defs):
        r      = _clamp(nuc_r + (i + 1) * orbit_spacing, nuc_r + 22, 158)
        rx     = r
        ry     = _clamp(r * (0.62 + i * 0.06), 20, 120)  # slight ellipse; inner more circular
        clr    = orb.get("color") or palette[i % len(palette)]
        n_elec = max(1, min(8, int(orb.get("electrons", 1 + i % 2))))
        speed  = round(1.6 / (1 + i * 0.45), 3)          # inner faster
        lbl    = str(orb.get("label", ""))[:14]
        direc  = 1 if i % 2 == 0 else -1                  # alternate CW/CCW
        orbits_js.append(
            f'{{"rx":{rx:.1f},"ry":{ry:.1f},"color":"{clr}",'
            f'"electrons":{n_elec},"speed":{speed},"label":"{lbl}",'
            f'"dir":{direc}}}'
        )

    orbits_json  = "[" + ",".join(orbits_js) + "]"
    nuc_color_js = COLORS.get(nuc_color, HIGHLIGHT)
    colors_js    = json.dumps(COLORS)
    bg           = BG_COLOR

    # Phase duration: how many seconds each shell is "newly highlighted" before the next
    phase_dur = max(1.5, base_dur / max(len(orbit_defs) + 1, 2))

    html = f"""<!DOCTYPE html><html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{background:{bg};display:flex;align-items:center;justify-content:center;
     width:100vw;height:100vh;overflow:hidden;flex-direction:column}}
canvas{{display:block;max-width:100%;max-height:85vh}}
#ctrl{{position:fixed;bottom:8px;right:10px;display:flex;gap:6px;z-index:9}}
button{{background:#2A3B2A;border:1px solid #4FC3F7;color:#F0EDD0;
        border-radius:4px;padding:4px 9px;font-size:13px;cursor:pointer}}
button:hover{{background:#3A4B3A}}
</style>
</head>
<body>
<canvas id="c" width="400" height="300"></canvas>
<div id="ctrl">
  <button id="btn-play" onclick="togglePlay()">⏸</button>
  <button onclick="replay()">🔁</button>
</div>
<script>
// ── Palette ──
const PALETTE = {colors_js};
function col(key){{return PALETTE[key]||key;}}

// ── Atom data (server-generated, no raw coords from LLM) ──
const NUC_LABEL  = "{nuc_label}";
const NUC_COLOR  = "{nuc_color_js}";
const NUC_R      = {nuc_r:.1f};
const ORBITS     = {orbits_json};
const CX = 200, CY = 150;
const PHASE_DUR  = {phase_dur:.2f};   // seconds each new shell is active

// ── Electron state ──
const electrons = [];
ORBITS.forEach((orb, oi) => {{
  for (let e = 0; e < orb.electrons; e++) {{
    electrons.push({{
      oi,
      angle: (2 * Math.PI * e) / orb.electrons,
    }});
  }}
}});

// ── Engine state ──
let t = 0, phase = 0, isPlaying = true, last = null;

// ── Non-uniform speed delta (inverse-radius law) ──
function angleDelta(angle, orb, dt) {{
  const rx = orb.rx, ry = orb.ry;
  const r = Math.hypot(rx * Math.cos(angle), ry * Math.sin(angle));
  return orb.dir * orb.speed * (rx / Math.max(r, 1)) * dt;
}}

// ── Opacity per orbit based on current phase ──
function shellOpacity(oi) {{
  if (oi < phase)   return 1.0;   // already revealed
  if (oi === phase) return 1.0;   // currently active
  return 0.18;                     // not yet revealed
}}

// ── Glow helper (canvas radial gradient) ──
function glow(ctx, x, y, r, hexColor, alpha) {{
  const [rv, gv, bv] = [1,3,5].map(o => parseInt(hexColor.slice(o,o+2),16));
  const g = ctx.createRadialGradient(x, y, 0, x, y, r*2.8);
  g.addColorStop(0, `rgba(${{rv}},${{gv}},${{bv}},${{alpha}})`);
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g;
  ctx.beginPath(); ctx.arc(x, y, r*2.8, 0, Math.PI*2); ctx.fill();
}}

// ── Render one frame ──
function render() {{
  const ctx = window._ctx;
  ctx.clearRect(0, 0, 400, 300);

  // ── Orbits ──
  ORBITS.forEach((orb, oi) => {{
    const op = shellOpacity(oi);
    const c  = col(orb.color);
    ctx.save();
    ctx.globalAlpha = op;
    ctx.strokeStyle = c;
    ctx.lineWidth   = op > 0.5 ? 1.2 : 0.7;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.ellipse(CX, CY, orb.rx, orb.ry, 0, 0, Math.PI*2);
    ctx.stroke();
    ctx.setLineDash([]);
    if (orb.label) {{
      ctx.fillStyle = c;
      ctx.font = '10px monospace';
      ctx.textBaseline = 'middle';
      ctx.fillText(orb.label, CX + orb.rx + 5, CY - 5);
    }}
    ctx.restore();
  }});

  // ── Electrons ──
  electrons.forEach(e => {{
    const orb = ORBITS[e.oi];
    const op  = shellOpacity(e.oi);
    const c   = col(orb.color);
    const x   = CX + orb.rx * Math.cos(e.angle);
    const y   = CY + orb.ry * Math.sin(e.angle);
    const active = e.oi <= phase;
    const r   = active ? 5.5 : 3.5;
    ctx.save();
    ctx.globalAlpha = op;
    if (active) glow(ctx, x, y, r, c, 0.45);
    ctx.fillStyle = c;
    ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI*2); ctx.fill();
    ctx.restore();
  }});

  // ── Nucleus ──
  const nActive = phase === 0;
  glow(ctx, CX, CY, NUC_R, NUC_COLOR, nActive ? 0.65 : 0.35);
  const ng = ctx.createRadialGradient(CX-NUC_R*0.3, CY-NUC_R*0.3, 1, CX, CY, NUC_R);
  ng.addColorStop(0, '#ffffff'); ng.addColorStop(1, NUC_COLOR);
  ctx.fillStyle = ng;
  ctx.beginPath(); ctx.arc(CX, CY, NUC_R, 0, Math.PI*2); ctx.fill();
  if (NUC_LABEL) {{
    ctx.fillStyle = '#1A2B1A';
    ctx.font = `bold ${{Math.max(9, NUC_R*0.55|0)}}px monospace`;
    ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(NUC_LABEL, CX, CY);
  }}
}}

// ── Update loop ──
function update(dt) {{
  electrons.forEach(e => {{
    e.angle += angleDelta(e.angle, ORBITS[e.oi], dt);
  }});
  // Phase advances over time — each shell revealed after PHASE_DUR seconds
  phase = Math.min(ORBITS.length, Math.floor(t / PHASE_DUR));
}}

function animate(now) {{
  if (last === null) last = now;
  const dt = Math.min((now - last) / 1000, 0.05);
  last = now;
  if (isPlaying) {{ t += dt; update(dt); }}
  render();
  requestAnimationFrame(animate);
}}

function togglePlay() {{
  isPlaying = !isPlaying;
  document.getElementById('btn-play').textContent = isPlaying ? '⏸' : '▶';
}}

function replay() {{
  t = 0; phase = 0; last = null;
  electrons.forEach((e, i) => {{
    const orb = ORBITS[e.oi];
    e.angle = (2 * Math.PI * (i % orb.electrons)) / orb.electrons;
  }});
}}

// ── Boot ──
window._ctx = document.getElementById('c').getContext('2d');
requestAnimationFrame(animate);
</script>
</body></html>"""
    return html


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

