"""
svg_renderers.py — Basic diagram renderers.

Generates shape-list descriptors for: triangle, circle_radius,
rectangle_area, line_graph, flow, comparison, cycle, labeled_diagram.
"""

import math
from app.utils.svg_colors import COLORS, STROKE, LABEL_COLOR, ACCENT, HIGHLIGHT, DIM, _clamp, _escape, _resolve_color

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

