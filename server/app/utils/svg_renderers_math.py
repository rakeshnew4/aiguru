"""
svg_renderers_math.py — Mathematics diagram renderers.

Generates shape-list descriptors for: number_line, fraction_bar,
graph_function, geometry_angles.
"""

import math
from app.utils.svg_colors import COLORS, STROKE, LABEL_COLOR, ACCENT, HIGHLIGHT, DIM, _clamp, _escape, _resolve_color

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

