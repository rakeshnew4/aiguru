"""
svg_primitives_ext.py — Extended SMIL shape renderer.

Handles: hexagon, half_circle, quarter_circle, human,
         waveform, polyline, orbit_electron.
Called by _render_shape in svg_primitives.py.
"""

import math
from app.utils.svg_colors import COLORS, STROKE, _clamp, _escape, _resolve_color


def _render_shape_ext(el: dict, shape: str, color: str, delay: float):
    """
    Handle extended shape types.
    Returns (svg_string, new_delay) tuple, or None if shape is unrecognised.
    """
    if shape == "hexagon":
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
            f'dur="{dur:.1f}s" begin="0s" repeatCount="indefinite"/>'
            f'</circle>'
        )
        return svg, delay + 0.28


    return None
