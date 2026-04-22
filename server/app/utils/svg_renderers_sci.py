"""
svg_renderers_sci.py — Science diagram renderers.

Generates shape-list descriptors for: atom (Bohr model),
solar_system, waveform_signal / sine_wave.
"""

import math
from app.utils.svg_colors import COLORS, STROKE, LABEL_COLOR, ACCENT, HIGHLIGHT, DIM, _clamp, _escape, _resolve_color

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
