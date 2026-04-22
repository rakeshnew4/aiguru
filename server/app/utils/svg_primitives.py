"""
svg_primitives.py — SMIL shape renderer.

Converts one shape dict to an SVG element string with SMIL animation.
Imported by svg_builder.py (build_animated_svg).
"""

import math
from app.utils.svg_colors import COLORS, STROKE, LABEL_COLOR, ACCENT, HIGHLIGHT, DIM, BG_COLOR, _STAGE_STEP, _clamp, _snap_x, _snap_y, _escape, _resolve_color

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

    # ── Extended shapes (hexagon, half_circle, quarter_circle, human,
    # ── waveform, polyline, orbit_electron) are handled in svg_primitives_ext.py
    result = _render_shape_ext(el, shape, color, delay)
    if result is not None:
        return result

    return "", delay


# Import after definition to avoid circular reference
from app.utils.svg_primitives_ext import _render_shape_ext  # noqa: E402
