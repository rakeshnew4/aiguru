"""
svg_atom.py — JS+SVG Bohr atom animation HTML generator.

Builds a self-contained HTML page (SVG + JS DOM) for the atom diagram.
Use build_atom_html(data) which returns the full HTML string.
"""

import json
import math
from app.utils.svg_colors import COLORS, STROKE, LABEL_COLOR, ACCENT, HIGHLIGHT, DIM, BG_COLOR, _clamp, _escape, _resolve_color

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

