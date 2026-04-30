'use strict';

// ── SVG namespace constant ────────────────────────────────────────────────────
const SVG_NS = 'http://www.w3.org/2000/svg';

function svgEl(tag, attrs) {
  const el = document.createElementNS(SVG_NS, tag);
  if (attrs) setAttrs(el, attrs);
  return el;
}

function setAttrs(el, attrs) {
  for (const [k, v] of Object.entries(attrs)) {
    el.setAttribute(k, v);
  }
}

function append(parent, ...children) {
  for (const ch of children) parent.appendChild(ch);
}

// ── Colour palette (matches Python svg_builder.py) ────────────────────────────
const COLORS = {
  stroke:    '#F0EDD0',  // chalk white
  label:     '#F5E3A0',  // chalk yellow
  highlight: '#FF6B6B',  // warm red / emphasis
  secondary: '#4FC3F7',  // sky blue
  dim:       '#8BAB8B',  // muted green
  bg:        '#1A2B1A',  // blackboard green
  orange:    '#FFB74D',
  green:     '#81C784',
  pink:      '#F48FB1',
  purple:    '#CE93D8',
  teal:      '#4DB6AC',
  yellow:    '#FFF176',
  red:       '#EF5350',
  blue:      '#42A5F5',
  gold:      '#FFD54F',
  coral:     '#FF7043',
  mint:      '#A8D8A8',
  white:     '#FFFFFF',
  sky:       '#87CEEB',
  brown:     '#A1887F',
};

function resolveColor(key) {
  if (!key) return COLORS.stroke;
  return COLORS[key] || key;
}

// ── SVG filter definitions ────────────────────────────────────────────────────

function injectDefs(svg) {
  const defs = svgEl('defs');

  // Glow filter (used by electrons, highlight nodes)
  const filter = svgEl('filter', { id: 'glow', x: '-50%', y: '-50%', width: '200%', height: '200%' });
  const blur   = svgEl('feGaussianBlur', { in: 'SourceGraphic', stdDeviation: '2.5', result: 'blur' });
  const merge  = svgEl('feMerge');
  append(merge,
    svgEl('feMergeNode', { in: 'blur' }),
    svgEl('feMergeNode', { in: 'SourceGraphic' }),
  );
  append(filter, blur, merge);

  // Arrowhead marker
  const marker = svgEl('marker', {
    id: 'arrow', markerWidth: '10', markerHeight: '7',
    refX: '9', refY: '3.5', orient: 'auto',
  });
  const arrowPath = svgEl('path', { d: 'M0,0 L10,3.5 L0,7 Z', fill: COLORS.secondary });
  append(marker, arrowPath);

  append(defs, filter, marker);
  svg.insertBefore(defs, svg.firstChild);
}

// ── DiagramEngine class ───────────────────────────────────────────────────────

class DiagramEngine {
  
  constructor(svg, descriptor, opts = {}) {
    this.svg        = svg;
    this.descriptor = descriptor;
    this.phaseMs    = opts.phaseMs  ?? 2500;
    this.loop       = opts.loop     ?? true;

    // Animation state
    this._running    = false;
    this._startTime  = null;    // performance.now() at last start
    this._pausedAt   = null;    // elapsed ms when paused
    this._rafId      = null;
    this._motions    = [];      // [{update(elapsed)}] registered by motion.js

    // Teaching phase state
    this._phases     = [];      // [{elements, startMs, endMs}]
    this._phaseIndex = -1;

    injectDefs(svg);
  }

  // ── Motion registration ───────────────────────────────────────────────────

  
  addMotion(motion) {
    this._motions.push(motion);
  }

  // ── Phase system ──────────────────────────────────────────────────────────

  
  addPhase(elements, startMs, endMs = -1) {
    // Initially hide phase elements
    for (const el of elements) el.setAttribute('opacity', '0');
    this._phases.push({ elements, startMs, endMs });
  }

  _updatePhases(elapsed) {
    for (const phase of this._phases) {
      const active = elapsed >= phase.startMs &&
                     (phase.endMs < 0 || elapsed < phase.endMs);
      const target = active ? '1' : '0';
      for (const el of phase.elements) {
        if (el.getAttribute('opacity') !== target) {
          el.setAttribute('opacity', target);
        }
      }
    }
  }

  // ── Animation loop ────────────────────────────────────────────────────────

  _tick(now) {
    if (!this._running) return;

    const base    = this._startTime;
    let elapsed   = now - base;

    // Phase wrap
    const totalMs = this._phases.length > 0
      ? this._phases[this._phases.length - 1].startMs + this.phaseMs
      : Infinity;

    if (this.loop && totalMs < Infinity && elapsed > totalMs + this.phaseMs) {
      this._startTime = now;
      elapsed = 0;
    }

    this._updatePhases(elapsed);
    for (const m of this._motions) m.update(elapsed);

    this._rafId = requestAnimationFrame(t => this._tick(t));
  }

  start() {
    if (this._running) return;
    this._running   = true;
    this._startTime = performance.now() - (this._pausedAt ?? 0);
    this._pausedAt  = null;
    this._rafId     = requestAnimationFrame(t => this._tick(t));
  }

  pause() {
    if (!this._running) return;
    this._running  = false;
    this._pausedAt = performance.now() - this._startTime;
    if (this._rafId) { cancelAnimationFrame(this._rafId); this._rafId = null; }
  }

  replay() {
    this.pause();
    this._pausedAt  = null;
    this._startTime = null;
    // Reset phase visibility
    for (const phase of this._phases) {
      for (const el of phase.elements) el.setAttribute('opacity', '0');
    }
    this.start();
  }

  toggle() {
    this._running ? this.pause() : this.start();
  }
}

// ── Control button factory ────────────────────────────────────────────────────

function createControls(engine, container) {
  const wrap = document.createElement('div');
  wrap.style.cssText = 'display:flex;gap:8px;justify-content:center;margin-top:6px;';

  function btn(label, title, onClick) {
    const b = document.createElement('button');
    b.textContent = label;
    b.title       = title;
    b.style.cssText = (
      'background:#2a3f2a;border:1px solid #4a6a4a;color:#e0f0e0;' +
      'border-radius:6px;padding:4px 12px;font-size:14px;cursor:pointer;'
    );
    b.addEventListener('click', onClick);
    return b;
  }

  const pauseBtn  = btn('⏸', 'Pause / Resume', () => engine.toggle());
  const replayBtn = btn('↺',  'Replay',          () => engine.replay());
  append(wrap, pauseBtn, replayBtn);
  (container ?? document.body).appendChild(wrap);
  return wrap;
}

// ── Exports (window globals for inline script access) ─────────────────────────
window.SVG_NS        = SVG_NS;
window.svgEl         = svgEl;
window.setAttrs      = setAttrs;
window.append        = append;
window.COLORS        = COLORS;
window.resolveColor  = resolveColor;
window.injectDefs    = injectDefs;
window.DiagramEngine = DiagramEngine;
window.createControls = createControls;

'use strict';

const Shapes = (() => {

  // ── Defaults ────────────────────────────────────────────────────────────────
  const DEF_STROKE  = () => resolveColor('stroke');
  const DEF_LABEL   = () => resolveColor('label');
  const DEF_SW      = 1.5;

  // ── circle ──────────────────────────────────────────────────────────────────
  
  function circle(cx, cy, r, opts = {}) {
    return svgEl('circle', {
      cx, cy, r,
      stroke:         resolveColor(opts.color) || DEF_STROKE(),
      fill:           opts.fill  ? resolveColor(opts.fill) : 'none',
      'stroke-width': opts.sw    ?? DEF_SW,
      opacity:        opts.opacity ?? 1,
      ...(opts.glow   ? { filter: 'url(#glow)' } : {}),
      ...(opts.id     ? { id: opts.id }           : {}),
    });
  }

  // ── ellipse ─────────────────────────────────────────────────────────────────
  
  function ellipse(cx, cy, rx, ry, opts = {}) {
    const el = svgEl('ellipse', {
      cx, cy, rx, ry,
      stroke:         resolveColor(opts.color) || DEF_STROKE(),
      fill:           opts.fill  ? resolveColor(opts.fill) : 'none',
      'stroke-width': opts.sw    ?? DEF_SW,
      opacity:        opts.opacity ?? 1,
      ...(opts.id     ? { id: opts.id }           : {}),
    });
    if (opts.rotate) {
      el.setAttribute('transform', `rotate(${opts.rotate},${cx},${cy})`);
    }
    return el;
  }

  // ── line ────────────────────────────────────────────────────────────────────
  
  function line(x1, y1, x2, y2, opts = {}) {
    const el = svgEl('line', {
      x1, y1, x2, y2,
      stroke:         resolveColor(opts.color) || DEF_STROKE(),
      'stroke-width': opts.sw    ?? DEF_SW,
      opacity:        opts.opacity ?? 1,
    });
    if (opts.dash) el.setAttribute('stroke-dasharray', opts.dash);
    return el;
  }

  
  function dottedLine(x1, y1, x2, y2, opts = {}) {
    return line(x1, y1, x2, y2, { dash: '4 3', sw: 1, ...opts });
  }

  // ── path ────────────────────────────────────────────────────────────────────
  
  function path(d, opts = {}) {
    const el = svgEl('path', {
      d,
      stroke:         resolveColor(opts.color) || DEF_STROKE(),
      fill:           opts.fill  ? resolveColor(opts.fill) : 'none',
      'stroke-width': opts.sw    ?? DEF_SW,
      opacity:        opts.opacity ?? 1,
    });
    if (opts.dash) el.setAttribute('stroke-dasharray', opts.dash);
    return el;
  }

  // ── text / label ─────────────────────────────────────────────────────────────
  
  function text(x, y, content, opts = {}) {
    const el = svgEl('text', {
      x, y,
      fill:               resolveColor(opts.color) || DEF_LABEL(),
      'font-size':        opts.size   ?? 12,
      'text-anchor':      opts.anchor ?? 'middle',
      'dominant-baseline': 'middle',
      'font-family':      'monospace, sans-serif',
      opacity:            opts.opacity ?? 1,
      ...(opts.weight ? { 'font-weight': opts.weight } : {}),
    });
    el.textContent = content;
    return el;
  }

  
  function label(x, y, content, opts = {}) {
    return text(x, y, content, { color: 'label', size: 11, ...opts });
  }

  // ── arc ─────────────────────────────────────────────────────────────────────
  
  function arc(cx, cy, r, startDeg, endDeg, opts = {}) {
    const toRad = d => d * Math.PI / 180;
    const x1 = cx + r * Math.cos(toRad(startDeg));
    const y1 = cy + r * Math.sin(toRad(startDeg));
    const x2 = cx + r * Math.cos(toRad(endDeg));
    const y2 = cy + r * Math.sin(toRad(endDeg));
    const large = Math.abs(endDeg - startDeg) > 180 ? 1 : 0;
    const d = `M ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2}`;
    return path(d, opts);
  }

  // ── arrow (straight, with arrowhead marker) ──────────────────────────────────
  
  function arrow(x1, y1, x2, y2, opts = {}) {
    return svgEl('line', {
      x1, y1, x2, y2,
      stroke:           resolveColor(opts.color) || resolveColor('secondary'),
      'stroke-width':   opts.sw ?? 1.5,
      'marker-end':     'url(#arrow)',
    });
  }

  // ── curvedArrow (quadratic bezier) ──────────────────────────────────────────
  
  function curvedArrow(x1, y1, x2, y2, cpx, cpy, opts = {}) {
    const d = `M ${x1} ${y1} Q ${cpx} ${cpy} ${x2} ${y2}`;
    return svgEl('path', {
      d,
      stroke:         resolveColor(opts.color) || resolveColor('secondary'),
      fill:           'none',
      'stroke-width': opts.sw ?? 1.5,
      'marker-end':   'url(#arrow)',
    });
  }

  // ── rect ─────────────────────────────────────────────────────────────────────
  
  function rect(x, y, w, h, opts = {}) {
    return svgEl('rect', {
      x, y, width: w, height: h,
      stroke:         resolveColor(opts.color) || DEF_STROKE(),
      fill:           opts.fill  ? resolveColor(opts.fill) : 'none',
      'stroke-width': opts.sw ?? DEF_SW,
      rx:             opts.rx ?? 0,
      opacity:        opts.opacity ?? 1,
    });
  }

  
  function roundedRect(x, y, w, h, opts = {}) {
    return rect(x, y, w, h, { rx: 6, ...opts });
  }

  // ── polygon ──────────────────────────────────────────────────────────────────
  
  function polygon(points, opts = {}) {
    return svgEl('polygon', {
      points: points.map(([x, y]) => `${x},${y}`).join(' '),
      stroke:         resolveColor(opts.color) || DEF_STROKE(),
      fill:           opts.fill  ? resolveColor(opts.fill) : 'none',
      'stroke-width': opts.sw ?? DEF_SW,
      opacity:        opts.opacity ?? 1,
    });
  }

  // ── radialLines (sun rays, atom nucleus spokes etc.) ─────────────────────────
  
  function radialLines(cx, cy, r1, r2, n, opts = {}) {
    const g      = svgEl('g');
    const start  = (opts.startAngle ?? 0) * Math.PI / 180;
    for (let i = 0; i < n; i++) {
      const angle = start + (i / n) * 2 * Math.PI;
      const cos   = Math.cos(angle);
      const sin   = Math.sin(angle);
      append(g, line(
        cx + r1 * cos, cy + r1 * sin,
        cx + r2 * cos, cy + r2 * sin,
        { color: opts.color, sw: opts.sw ?? 1.5 },
      ));
    }
    return g;
  }

  // ── grid ────────────────────────────────────────────────────────────────────
  
  function grid(x, y, w, h, cols, rows, opts = {}) {
    const g  = svgEl('g');
    const cw = w / cols;
    const rh = h / rows;
    for (let c = 0; c <= cols; c++) {
      append(g, line(x + c * cw, y, x + c * cw, y + h, { color: opts.color, sw: opts.sw ?? 0.5, dash: '2 2' }));
    }
    for (let r = 0; r <= rows; r++) {
      append(g, line(x, y + r * rh, x + w, y + r * rh, { color: opts.color, sw: opts.sw ?? 0.5, dash: '2 2' }));
    }
    return g;
  }

  // ── angleArc (small arc near vertex to denote an angle) ─────────────────────
  
  function angleArc(vx, vy, angle1Deg, angle2Deg, r, opts = {}) {
    return arc(vx, vy, r, angle1Deg, angle2Deg, { sw: 1.2, ...opts });
  }

  // ── axis tick mark ───────────────────────────────────────────────────────────
  
  function tick(x, y, horizontal, size = 4, opts = {}) {
    return horizontal
      ? line(x, y - size, x, y + size, opts)
      : line(x - size, y, x + size, y, opts);
  }

  // ── Public API ───────────────────────────────────────────────────────────────
  return {
    circle, ellipse, line, dottedLine, path,
    text, label,
    arc, arrow, curvedArrow,
    rect, roundedRect, polygon,
    radialLines, grid,
    angleArc, tick,
  };
})();

window.Shapes = Shapes;

'use strict';

const Motion = (() => {

  // ── orbit ────────────────────────────────────────────────────────────────────
  
  function orbit(el, cx, cy, rx, ry, periodMs, opts = {}) {
    const phase  = (opts.phase  ?? 0) * 2 * Math.PI;
    const dir    = opts.ccw ? -1 : 1;
    const useCxy = opts.useCxy !== false;

    return {
      update(elapsed) {
        const angle = dir * (elapsed / periodMs) * 2 * Math.PI + phase;
        const x = cx + rx * Math.cos(angle);
        const y = cy + ry * Math.sin(angle);
        if (useCxy) {
          el.setAttribute('cx', x);
          el.setAttribute('cy', y);
        } else {
          el.setAttribute('x', x);
          el.setAttribute('y', y);
        }
      },
    };
  }

  // ── oscillate ────────────────────────────────────────────────────────────────
  
  function oscillate(el, attr, centre, amplitude, periodMs, opts = {}) {
    const phase = (opts.phase ?? 0) * 2 * Math.PI;
    return {
      update(elapsed) {
        const val = centre + amplitude * Math.sin((elapsed / periodMs) * 2 * Math.PI + phase);
        el.setAttribute(attr, val);
      },
    };
  }

  // ── linearPath ───────────────────────────────────────────────────────────────
  
  function linearPath(el, x1, y1, x2, y2, periodMs, opts = {}) {
    const useCxy = opts.useCxy !== false;
    return {
      update(elapsed) {
        let t = (elapsed % (opts.pingpong ? periodMs * 2 : periodMs)) / periodMs;
        if (opts.pingpong && t > 1) t = 2 - t;  // reverse on return
        const x = x1 + (x2 - x1) * t;
        const y = y1 + (y2 - y1) * t;
        if (useCxy) {
          el.setAttribute('cx', x);
          el.setAttribute('cy', y);
        } else {
          el.setAttribute('x', x);
          el.setAttribute('y', y);
        }
      },
    };
  }

  // ── pulse ────────────────────────────────────────────────────────────────────
  
  function pulse(el, cx, cy, minScale, maxScale, periodMs) {
    return {
      update(elapsed) {
        const t     = 0.5 - 0.5 * Math.cos((elapsed / periodMs) * 2 * Math.PI);
        const scale = minScale + (maxScale - minScale) * t;
        el.setAttribute('transform', `translate(${cx},${cy}) scale(${scale}) translate(${-cx},${-cy})`);
      },
    };
  }

  // ── fade ─────────────────────────────────────────────────────────────────────
  
  function fade(el, minOpacity, maxOpacity, periodMs, opts = {}) {
    const phase = (opts.phase ?? 0) * 2 * Math.PI;
    return {
      update(elapsed) {
        const t  = 0.5 - 0.5 * Math.cos((elapsed / periodMs) * 2 * Math.PI + phase);
        const op = minOpacity + (maxOpacity - minOpacity) * t;
        el.setAttribute('opacity', op);
      },
    };
  }

  // ── rotate ───────────────────────────────────────────────────────────────────
  
  function rotate(el, cx, cy, periodMs, opts = {}) {
    const dir = opts.ccw ? -1 : 1;
    return {
      update(elapsed) {
        const deg = dir * (elapsed / periodMs) * 360;
        el.setAttribute('transform', `rotate(${deg},${cx},${cy})`);
      },
    };
  }

  // ── wave ─────────────────────────────────────────────────────────────────────
  
  function wave(pathEl, x0, x1, cy, amplitude, wavelength, periodMs) {
    return {
      update(elapsed) {
        const phase = (elapsed / periodMs) * 2 * Math.PI;
        const pts   = [];
        const steps = Math.ceil((x1 - x0) / 4);
        for (let i = 0; i <= steps; i++) {
          const x = x0 + (i / steps) * (x1 - x0);
          const y = cy + amplitude * Math.sin((x / wavelength) * 2 * Math.PI - phase);
          pts.push(i === 0 ? `M ${x} ${y}` : `L ${x} ${y}`);
        }
        pathEl.setAttribute('d', pts.join(' '));
      },
    };
  }

  // ── Public API ───────────────────────────────────────────────────────────────
  return { orbit, oscillate, linearPath, pulse, fade, rotate, wave };
})();

window.Motion = Motion;

'use strict';

const Diagrams = (() => {

  // ── helpers ──────────────────────────────────────────────────────────────────

  
  const W = 400, H = 300;
  const CX = W / 2, CY = H / 2;

  function g(attrs) { return svgEl('g', attrs || {}); }

  // Orbital period model: closer shells orbit faster (inverse-radius law)
  function _periodForRadius(r) { return 2000 + r * 18; }

  // Electron colours per shell
  const SHELL_COLORS = ['#4FC3F7', '#FF6B6B', '#81C784', '#FFB74D', '#CE93D8'];

  // ── atom ─────────────────────────────────────────────────────────────────────
  
  function atom(engine, svg, data) {
    const symbol   = data.symbol   || 'H';
    const protons  = data.protons  || 1;
    const neutrons = data.neutrons || 0;

    // Derive shells from protons if not given
    let shells = data.shells;
    if (!Array.isArray(shells) || shells.length === 0) {
      shells = _shellsFromProtons(protons);
    }

    const MAX_SHELLS  = 5;
    const NSHELLS     = Math.min(shells.length, MAX_SHELLS);
    const MAX_R       = Math.min(CX, CY) - 18;
    const MIN_R       = 28;
    const shellGap    = NSHELLS > 1 ? (MAX_R - MIN_R) / (NSHELLS - 1) : 0;

    const nucleusR    = Math.max(12, Math.min(20, 8 + protons * 0.6));

    // ── Nucleus ────────────────────────────────────────────────────────────
    const nucleus = Shapes.circle(CX, CY, nucleusR, {
      color: 'highlight', fill: 'coral', sw: 2, glow: true,
    });
    append(svg, nucleus);
    engine.addMotion(Motion.pulse(nucleus, CX, CY, 0.92, 1.06, 1800));

    // Nucleus label
    append(svg, Shapes.text(CX, CY - 1, symbol, {
      color: 'white', size: Math.max(9, nucleusR - 4), weight: 'bold',
    }));
    if (protons + neutrons > 0) {
      append(svg, Shapes.text(CX + nucleusR + 5, CY - 6,
        `${protons}p`, { color: 'orange', size: 9, anchor: 'start' }));
      if (neutrons > 0) {
        append(svg, Shapes.text(CX + nucleusR + 5, CY + 6,
          `${neutrons}n`, { color: 'dim', size: 9, anchor: 'start' }));
      }
    }

    // ── Shells + Electrons ─────────────────────────────────────────────────
    for (let s = 0; s < NSHELLS; s++) {
      const r       = MIN_R + s * shellGap;
      const count   = Math.min(shells[s], 18);   // cap for visual sanity
      const color   = SHELL_COLORS[s % SHELL_COLORS.length];
      const period  = _periodForRadius(r);
      const ccw     = s % 2 === 1;               // alternate direction

      // Orbit ring
      append(svg, Shapes.ellipse(CX, CY, r, r * 0.38, {
        color: 'dim', sw: 0.6,
      }));

      // Shell label (n=1, n=2, …)
      append(svg, Shapes.text(CX + r + 4, CY - 3, `n=${s + 1}`, {
        color: 'dim', size: 8, anchor: 'start',
      }));

      // Electrons
      for (let e = 0; e < count; e++) {
        const phaseOffset = e / count;
        const el = Shapes.circle(0, 0, 4, { color, fill: color, glow: true });
        append(svg, el);
        engine.addMotion(Motion.orbit(
          el, CX, CY, r, r * 0.38, period,
          { phase: phaseOffset, ccw },
        ));
      }
    }

    // Shell count legend
    const legendY = H - 14;
    append(svg, Shapes.text(CX, legendY,
      `Shell config: ${shells.slice(0, NSHELLS).join(', ')}`,
      { color: 'label', size: 9 }));
  }

  function _shellsFromProtons(p) {
    // Standard electron configuration for first 36 elements
    const CAPS = [2, 8, 18, 32, 18, 8];
    const result = [];
    let remaining = p;
    for (const cap of CAPS) {
      if (remaining <= 0) break;
      const fill = Math.min(remaining, cap);
      result.push(fill);
      remaining -= fill;
    }
    return result;
  }

  // ── solarSystem ──────────────────────────────────────────────────────────────
  
  function solarSystem(engine, svg, data) {
    const showLabels = data.showLabels !== false;
    const planetDefs = data.planets || _defaultPlanets();

    // Sun
    const sunR = 22;
    const sun = Shapes.circle(CX, CY, sunR, { color: 'gold', fill: 'gold', glow: true });
    append(svg, sun);
    engine.addMotion(Motion.pulse(sun, CX, CY, 0.95, 1.05, 2200));
    append(svg, Shapes.radialLines(CX, CY, sunR + 2, sunR + 9, 12,
      { color: 'gold', sw: 1 }));

    const MAX_ORBIT = Math.min(CX, CY) - 16;
    const orbitStep = (MAX_ORBIT - sunR - 14) / Math.max(planetDefs.length, 1);

    planetDefs.slice(0, 6).forEach((p, i) => {
      const orbitR  = p.orbitR ?? (sunR + 14 + i * orbitStep);
      const pR      = p.radius ?? Math.max(3, 7 - i);
      // Accept 'periodMs' or 'duration' (seconds→ms) from LLM schema
      const period  = p.periodMs ?? (p.duration ? p.duration * 1000 : (1500 + i * 900));
      const color   = p.color || SHELL_COLORS[i % SHELL_COLORS.length];
      const ccw     = i % 3 === 2;

      // Orbit ring
      append(svg, Shapes.ellipse(CX, CY, orbitR, orbitR * 0.36, {
        color: 'dim', sw: 0.5,
      }));

      // Planet
      const el = Shapes.circle(0, 0, pR, { color, fill: color, glow: true });
      append(svg, el);
      engine.addMotion(Motion.orbit(
        el, CX, CY, orbitR, orbitR * 0.36, period,
        { phase: i / planetDefs.length, ccw },
      ));

      if (showLabels && (p.name || p.label)) {
        const labelEl = Shapes.text(0, 0, p.name || p.label, { color: 'label', size: 8 });
        append(svg, labelEl);
        // Label follows planet
        engine.addMotion({
          update(elapsed) {
            const angle = (ccw ? -1 : 1) * (elapsed / period) * 2 * Math.PI +
                          (i / planetDefs.length) * 2 * Math.PI;
            const lx = CX + orbitR * Math.cos(angle);
            const ly = CY + orbitR * 0.36 * Math.sin(angle) - pR - 5;
            labelEl.setAttribute('x', lx);
            labelEl.setAttribute('y', ly);
          },
        });
      }
    });
  }

  function _defaultPlanets() {
    return [
      { name: 'Mercury', color: '#A0A0A0', radius: 3 },
      { name: 'Venus',   color: '#FFB74D', radius: 5 },
      { name: 'Earth',   color: '#42A5F5', radius: 5 },
      { name: 'Mars',    color: '#EF5350', radius: 4 },
      { name: 'Jupiter', color: '#FFD54F', radius: 8 },
      { name: 'Saturn',  color: '#CE93D8', radius: 7 },
    ];
  }

  // ── wave (sine / sound / EM wave) ─────────────────────────────────────────────
  
  function wave(engine, svg, data) {
    const amplitude  = data.amplitude  ?? 40;
    const wavelength = data.wavelength ?? 80;
    const color      = data.color      || 'secondary';
    const showAxes   = data.showAxes   !== false;
    // Accept 'label' or 'title' (LLM schema uses 'title')
    const waveLabel  = data.label || data.title || '';
    const cy         = H / 2;
    const x0 = 30, x1 = W - 30;

    if (showAxes) {
      // X-axis
      append(svg, Shapes.arrow(x0, cy, x1, cy, { color: 'dim', sw: 1 }));
      // Y-axis
      append(svg, Shapes.arrow(x0, cy + amplitude + 15, x0, cy - amplitude - 15,
        { color: 'dim', sw: 1 }));
      append(svg, Shapes.label(x1 - 5, cy + 12, 'x', { anchor: 'end' }));
      append(svg, Shapes.label(x0 + 5, cy - amplitude - 12, 'y', { anchor: 'start' }));

      // Wavelength marker
      append(svg, Shapes.dottedLine(x0 + 5, cy - amplitude - 8,
        x0 + 5 + wavelength, cy - amplitude - 8, { color: 'label' }));
      append(svg, Shapes.label(x0 + 5 + wavelength / 2, cy - amplitude - 15, 'λ',
        { color: 'yellow', size: 11 }));
    }

    // Wave path (animated)
    const wavePath = Shapes.path('', { color, sw: 2 });
    append(svg, wavePath);
    engine.addMotion(Motion.wave(wavePath, x0, x1, cy, amplitude, wavelength, 2000));

    // Amplitude arrow
    const ampArrow = Shapes.dottedLine(x0 + 8, cy, x0 + 8, cy - amplitude, { color: 'highlight' });
    append(svg, ampArrow);
    append(svg, Shapes.label(x0 + 18, cy - amplitude / 2, 'A', { color: 'highlight' }));

    if (waveLabel) {
      append(svg, Shapes.text(CX, 18, waveLabel, { color: 'label', size: 13, weight: 'bold' }));
    }
  }

  // ── sun (procedural) ──────────────────────────────────────────────────────────
  
  function sun(engine, svg, data) {
    const rays  = data.rays  ?? 12;
    const label = data.label ?? 'Sun';
    const r     = 35;

    // Outer glow
    const glow = Shapes.circle(CX, CY, r + 10, {
      fill: 'gold', sw: 0, opacity: 0.18,
    });
    append(svg, glow);
    engine.addMotion(Motion.pulse(glow, CX, CY, 0.85, 1.1, 2000));

    // Ray group (rotates)
    const rayGroup = svgEl('g');
    for (let i = 0; i < rays; i++) {
      const angle = (i / rays) * 2 * Math.PI;
      const x1 = CX + (r + 4) * Math.cos(angle);
      const y1 = CY + (r + 4) * Math.sin(angle);
      const x2 = CX + (r + 14) * Math.cos(angle);
      const y2 = CY + (r + 14) * Math.sin(angle);
      append(rayGroup, Shapes.line(x1, y1, x2, y2, { color: 'gold', sw: 2 }));
    }
    append(svg, rayGroup);
    engine.addMotion(Motion.rotate(rayGroup, CX, CY, 12000));

    // Sun body
    append(svg, Shapes.circle(CX, CY, r, { color: 'gold', fill: 'yellow', glow: true }));

    if (label) {
      append(svg, Shapes.text(CX, CY + r + 18, label,
        { color: 'label', size: 12, weight: 'bold' }));
    }
  }

  // ── plant (branching, procedural) ────────────────────────────────────────────
  
  function plant(engine, svg, data) {
    const label  = data.label  ?? 'Plant';
    const leaves = data.leaves ?? 2;
    const baseX  = CX, baseY  = H - 30;
    const stemH  = 80;

    // Stem
    append(svg, Shapes.line(baseX, baseY, baseX, baseY - stemH,
      { color: 'green', sw: 3 }));

    // Leaves (pairs)
    for (let i = 0; i < leaves; i++) {
      const ly = baseY - 20 - i * (stemH / (leaves + 1));
      const leftLeaf  = Shapes.ellipse(baseX - 16, ly - 5, 14, 8,
        { color: 'green', fill: 'mint', sw: 1.5, rotate: -30 });
      const rightLeaf = Shapes.ellipse(baseX + 16, ly - 5, 14, 8,
        { color: 'green', fill: 'mint', sw: 1.5, rotate: 30 });
      append(svg, leftLeaf, rightLeaf);
      // Subtle leaf pulse
      engine.addMotion(Motion.pulse(leftLeaf,  baseX - 16, ly - 5, 0.95, 1.05, 2000 + i * 400));
      engine.addMotion(Motion.pulse(rightLeaf, baseX + 16, ly - 5, 0.95, 1.05, 2200 + i * 400));
    }

    // Flower / bud at top
    const topY = baseY - stemH;
    append(svg, Shapes.circle(baseX, topY - 10, 9, { color: 'pink', fill: 'pink', glow: true }));
    engine.addMotion(Motion.pulse(
      svg.lastElementChild, baseX, topY - 10, 0.9, 1.1, 1500,
    ));

    if (label) {
      append(svg, Shapes.text(CX, topY - 26, label,
        { color: 'label', size: 12, weight: 'bold' }));
    }
  }

  // ── flowArrow (labelled linear process flow) ──────────────────────────────────
  
  function flowArrow(engine, svg, data) {
    const steps = (data.steps || ['Step 1', 'Step 2', 'Step 3']).slice(0, 5);
    const title = data.title || '';
    const n     = steps.length;
    const pad   = 20;
    const boxW  = Math.min(60, (W - 2 * pad - (n - 1) * 16) / n);
    const boxH  = 36;
    const cy    = CY;

    const totalW = n * boxW + (n - 1) * 16;
    const startX = (W - totalW) / 2;

    if (title) {
      append(svg, Shapes.text(CX, 20, title,
        { color: 'label', size: 13, weight: 'bold' }));
    }

    steps.forEach((rawStep, i) => {
      const step = typeof rawStep === 'string' ? rawStep : (rawStep.label || String(rawStep));
      const x = startX + i * (boxW + 16);
      // Box — phases in step by step
      const box = Shapes.roundedRect(x, cy - boxH / 2, boxW, boxH, {
        color: 'secondary', fill: 'bg', sw: 1.5, opacity: 0,
      });
      const lbl = Shapes.text(x + boxW / 2, cy, step,
        { color: 'label', size: 9, opacity: 0 });
      append(svg, box, lbl);
      engine.addPhase([box, lbl], i * 500, -1);

      // Arrow to next box
      if (i < n - 1) {
        const arw = Shapes.arrow(
          x + boxW + 2, cy, x + boxW + 14, cy,
          { color: 'secondary', sw: 1.5 },
        );
        arw.setAttribute('opacity', 0);
        append(svg, arw);
        engine.addPhase([arw], i * 500 + 250, -1);
      }
    });
  }

  // ── cycle (cyclical process diagram) ──────────────────────────────────────────
  
  function cycle(engine, svg, data) {
    const steps = (data.steps || ['Evaporation', 'Condensation', 'Precipitation']).slice(0, 6);
    const title = data.title || '';
    const n     = steps.length;

    if (title) {
      append(svg, Shapes.text(CX, 18, title, { color: 'label', size: 13, weight: 'bold' }));
    }

    const titleH = title ? 26 : 10;
    const cx = CX, cy = CY + titleH * 0.35;
    const ringR  = Math.min(CX - 52, CY - titleH - 32);

    // Faint ring guideline
    append(svg, Shapes.circle(cx, cy, ringR, { color: 'dim', sw: 0.4 }));

    steps.forEach((rawStep, i) => {
      const step  = typeof rawStep === 'string' ? rawStep : (rawStep.label || String(rawStep));
      const angle     = (i / n) * 2 * Math.PI - Math.PI / 2;
      const nx        = cx + ringR * Math.cos(angle);
      const ny        = cy + ringR * Math.sin(angle);
      const color     = (typeof rawStep === 'object' && rawStep.color)
        ? resolveColor(rawStep.color)
        : SHELL_COLORS[i % SHELL_COLORS.length];

      // Curved arrow to the next step
      const nextAngle = ((i + 1) / n) * 2 * Math.PI - Math.PI / 2;
      const nnx       = cx + ringR * Math.cos(nextAngle);
      const nny       = cy + ringR * Math.sin(nextAngle);
      const midAngle  = angle + Math.PI / n;
      const mx        = cx + (ringR + 22) * Math.cos(midAngle);
      const my        = cy + (ringR + 22) * Math.sin(midAngle);

      const arw = Shapes.curvedArrow(nx, ny, nnx, nny, mx, my,
        { color: 'secondary', sw: 1.5 });
      arw.setAttribute('opacity', 0);
      append(svg, arw);
      engine.addPhase([arw], i * 450 + 300, -1);

      // Node box
      const boxW = 62, boxH = 26;
      const box  = Shapes.roundedRect(nx - boxW / 2, ny - boxH / 2, boxW, boxH,
        { color, fill: 'bg', sw: 1.5, opacity: 0 });
      const lbl  = Shapes.text(nx, ny, step, { color, size: 8.5, opacity: 0 });
      append(svg, box, lbl);
      engine.addPhase([box, lbl], i * 450, -1);
    });
  }

  // ── labeled (labeled diagram — anatomy, cell, structure) ──────────────────────
  
  function labeled(engine, svg, data) {
    const title    = data.title        || '';
    const center   = data.center       || 'Cell';
    // Normalise center_shape: "rectangle" → "rect", anything else falls back to "circle"
    const centerShRaw = (data.center_shape || 'circle').toLowerCase();
    const centerSh = (centerShRaw === 'rect' || centerShRaw === 'rectangle') ? 'rect' : 'circle';
    const rawParts = (data.parts || ['Nucleus', 'Membrane', 'Cytoplasm']).slice(0, 8);
    // parts can be strings OR objects {label, color, ...}
    const parts = rawParts.map(p => (typeof p === 'string' ? { label: p } : p));
    const n     = parts.length;

    if (title) {
      append(svg, Shapes.text(CX, 16, title, { color: 'label', size: 13, weight: 'bold' }));
    }

    const titleH = title ? 24 : 0;
    const cx = CX, cy = CY + titleH * 0.35;
    const innerR = 38;
    const outerR = Math.min(CX - 40, CY - titleH - 28);

    // Center shape (always visible — not phased)
    if (centerSh === 'rect') {
      append(svg, Shapes.roundedRect(cx - innerR, cy - innerR * 0.7, innerR * 2, innerR * 1.4,
        { color: 'highlight', fill: 'bg', sw: 2 }));
    } else {
      const cEl = Shapes.circle(cx, cy, innerR, { color: 'highlight', fill: 'bg', sw: 2 });
      append(svg, cEl);
      engine.addMotion(Motion.pulse(cEl, cx, cy, 0.96, 1.04, 2400));
    }
    append(svg, Shapes.text(cx, cy, center, { color: 'highlight', size: 11, weight: 'bold' }));

    // Radial parts
    parts.forEach((part, i) => {
      const angle     = (i / n) * 2 * Math.PI - Math.PI / 2;
      const partLabel = part.label || String(part);
      // Use part-specified color if provided, fall back to SHELL_COLORS
      const color     = part.color ? resolveColor(part.color) : SHELL_COLORS[i % SHELL_COLORS.length];

      // If part has explicit offset, use it; otherwise place on ring
      let px, py;
      if (Array.isArray(part.offset)) {
        px = cx + part.offset[0];
        py = cy + part.offset[1];
      } else {
        px = cx + outerR * Math.cos(angle);
        py = cy + outerR * Math.sin(angle);
      }

      // Dotted connector from nucleus edge to part box edge
      const lx1 = cx + (innerR + 5) * Math.cos(angle);
      const ly1 = cy + (innerR + 5) * Math.sin(angle);
      const lx2 = px - 24 * Math.cos(angle);
      const ly2 = py - 12 * Math.sin(angle);
      const connector = Shapes.dottedLine(lx1, ly1, lx2, ly2, { color: 'dim', sw: 1 });
      connector.setAttribute('opacity', 0);
      append(svg, connector);

      const boxW = 64, boxH = 22;
      const box  = Shapes.roundedRect(px - boxW / 2, py - boxH / 2, boxW, boxH,
        { color, fill: 'bg', sw: 1.5, opacity: 0 });
      const lbl  = Shapes.text(px, py, partLabel, { color, size: 8, opacity: 0 });
      append(svg, box, lbl);
      engine.addPhase([connector, box, lbl], i * 320, -1);
    });
  }

  // ── comparison (side-by-side A vs B) ──────────────────────────────────────────
  
  function comparison(engine, svg, data) {
    const title      = data.title        || '';
    const leftTitle  = data.left         || 'A';
    const rightTitle = data.right        || 'B';
    const leftPts    = (data.left_points  || []).slice(0, 4);
    const rightPts   = (data.right_points || []).slice(0, 4);

    const titleH  = title ? 26 : 14;
    const colW    = 148;
    const lx      = CX - colW / 2 - 14;
    const rx      = CX + colW / 2 + 14;
    const headerY = titleH;

    if (title) {
      append(svg, Shapes.text(CX, 14, title, { color: 'label', size: 12, weight: 'bold' }));
    }

    // Centre divider + VS label
    append(svg, Shapes.line(CX, headerY + 4, CX, H - 16, { color: 'dim', sw: 1, dash: '5 4' }));
    append(svg, Shapes.text(CX, headerY - 1, 'VS', { color: 'highlight', size: 10, weight: 'bold' }));

    // Left header
    const lBox = Shapes.roundedRect(lx - colW / 2, headerY + 5, colW, 26,
      { color: 'secondary', fill: 'bg', sw: 1.5, opacity: 0 });
    const lTxt = Shapes.text(lx, headerY + 18, leftTitle,
      { color: 'secondary', size: 10, weight: 'bold', opacity: 0 });
    append(svg, lBox, lTxt);
    engine.addPhase([lBox, lTxt], 0, -1);

    // Right header
    const rBox = Shapes.roundedRect(rx - colW / 2, headerY + 5, colW, 26,
      { color: 'highlight', fill: 'bg', sw: 1.5, opacity: 0 });
    const rTxt = Shapes.text(rx, headerY + 18, rightTitle,
      { color: 'highlight', size: 10, weight: 'bold', opacity: 0 });
    append(svg, rBox, rTxt);
    engine.addPhase([rBox, rTxt], 200, -1);

    // Bullet points
    const ptY0   = headerY + 38;
    const ptH    = 24;
    const maxPts = Math.max(leftPts.length, rightPts.length);
    for (let i = 0; i < maxPts; i++) {
      const y = ptY0 + i * ptH;
      if (leftPts[i]) {
        const el = Shapes.text(lx, y + ptH * 0.5, '\u2022 ' + leftPts[i],
          { color: 'label', size: 8.5, opacity: 0 });
        append(svg, el);
        engine.addPhase([el], i * 350 + 480, -1);
      }
      if (rightPts[i]) {
        const el = Shapes.text(rx, y + ptH * 0.5, '\u2022 ' + rightPts[i],
          { color: 'label', size: 8.5, opacity: 0 });
        append(svg, el);
        engine.addPhase([el], i * 350 + 680, -1);
      }
    }
  }

  // ── custom (free-form path-based drawing) ─────────────────────────────────────
  
  function custom(engine, svg, data) {
    const title    = data.title    || '';
    const elements = data.elements || [];

    if (title) {
      append(svg, Shapes.text(CX, 16, title, { color: 'label', size: 13, weight: 'bold' }));
    }

    elements.forEach((el, i) => {
      const type  = (el.type  || 'text').toLowerCase();
      const color = el.color  || 'label';
      const delay = el.delay  != null ? Number(el.delay) : i * 220;
      let node;

      switch (type) {
        case 'path':
          node = Shapes.path(el.d || '', { color, sw: el.sw || 1.5,
            fill: el.fill || undefined });
          break;
        case 'circle':
          node = Shapes.circle(el.cx ?? CX, el.cy ?? CY, el.r ?? 20,
            { color, fill: el.fill || undefined, sw: el.sw || 1.5 });
          break;
        case 'rect':
          node = Shapes.roundedRect(el.x ?? 0, el.y ?? 0, el.w ?? 60, el.h ?? 30,
            { color, fill: el.fill || undefined, sw: el.sw || 1.5 });
          break;
        case 'text':
          node = Shapes.text(el.x ?? CX, el.y ?? CY, String(el.text || ''),
            { color, size: el.size || 11 });
          break;
        case 'line':
          node = Shapes.line(el.x1 ?? 0, el.y1 ?? 0, el.x2 ?? 0, el.y2 ?? 0,
            { color, sw: el.sw || 1.5 });
          break;
        case 'arrow':
          node = Shapes.arrow(el.x1 ?? 0, el.y1 ?? 0, el.x2 ?? 0, el.y2 ?? 0,
            { color, sw: el.sw || 1.5 });
          break;
        default:
          return;
      }

      node.setAttribute('opacity', 0);
      append(svg, node);

      if (el.animate === 'pulse') {
        const pcx = el.cx ?? (el.x != null ? (el.x + (el.w || 60) / 2) : CX);
        const pcy = el.cy ?? (el.y != null ? (el.y + (el.h || 30) / 2) : CY);
        engine.addMotion(Motion.pulse(node, pcx, pcy, 0.91, 1.09, 1800));
      } else if (el.animate === 'fade') {
        engine.addMotion(Motion.fade(node, 0.3, 1.0, 2500));
      }
      engine.addPhase([node], delay, -1);
    });
  }

  // ── heart ────────────────────────────────────────────────────────────────────
  
  function heart(engine, svg, data) {
    const label = data.label || 'Human Heart';
    const cx = CX, cy = CY + 8;

    // Parametric heart outline: x=16sin³t, y=13cost-5cos2t-2cos3t-cos4t
    const pts = [];
    for (let i = 0; i <= 62; i++) {
      const t = (i / 62) * 2 * Math.PI;
      const x = cx + 5.5 * 16 * Math.pow(Math.sin(t), 3);
      const y = cy - 5.5 * (13 * Math.cos(t) - 5 * Math.cos(2 * t)
                            - 2 * Math.cos(3 * t) - Math.cos(4 * t));
      pts.push(i === 0 ? `M ${x.toFixed(1)} ${y.toFixed(1)}`
                       : `L ${x.toFixed(1)} ${y.toFixed(1)}`);
    }
    const hPath = Shapes.path(pts.join(' ') + ' Z',
      { color: 'highlight', fill: 'highlight', sw: 2 });
    hPath.setAttribute('fill-opacity', '0.28');
    append(svg, hPath);
    engine.addMotion(Motion.pulse(hPath, cx, cy, 0.92, 1.08, 850));

    // Septal divider
    append(svg, Shapes.dottedLine(cx, cy - 22, cx, cy + 58, { color: 'stroke', sw: 1 }));

    // Chamber labels
    append(svg, Shapes.text(cx - 30, cy + 12, 'R',
      { color: 'secondary', size: 16, weight: 'bold' }));
    append(svg, Shapes.text(cx + 30, cy + 12, 'L',
      { color: 'highlight', size: 16, weight: 'bold' }));

    // Blood flow particles (orbit each ventricle)
    const dotR = Shapes.circle(cx - 22, cy, 5,
      { fill: 'secondary', color: 'secondary', glow: true });
    const dotL = Shapes.circle(cx + 22, cy, 5,
      { fill: 'highlight', color: 'highlight', glow: true });
    append(svg, dotR, dotL);
    engine.addMotion(Motion.orbit(dotR, cx - 22, cy + 5, 30, 26, 1800, { phase: 0 }));
    engine.addMotion(Motion.orbit(dotL, cx + 22, cy + 5, 30, 26, 2000, { phase: 0.5, ccw: true }));

    // Vessels
    append(svg, Shapes.line(cx - 18, cy - 70, cx - 55, cy - 108,
      { color: 'secondary', sw: 2 }));
    append(svg, Shapes.line(cx + 18, cy - 70, cx + 55, cy - 108,
      { color: 'highlight', sw: 2 }));
    append(svg, Shapes.text(cx - 58, cy - 112, 'To lungs',
      { color: 'secondary', size: 8, anchor: 'end' }));
    append(svg, Shapes.text(cx + 58, cy - 112, 'To body',
      { color: 'highlight', size: 8, anchor: 'start' }));

    if (label) {
      append(svg, Shapes.text(CX, 14, label,
        { color: 'label', size: 13, weight: 'bold' }));
    }
  }

  // ── neuron ───────────────────────────────────────────────────────────────────
  
  function neuron(engine, svg, data) {
    const label  = data.label || 'Neuron';
    const somaX  = 108, somaY = CY, somaR = 24;

    // Dendrites (five branches at fixed angles)
    const dendAngles = [-110, -145, -170, -70, -40];
    dendAngles.forEach((deg, i) => {
      const a   = deg * Math.PI / 180;
      const len = 32 + i * 5;
      const x1  = somaX + Math.cos(a) * somaR;
      const y1  = somaY + Math.sin(a) * somaR;
      const x2  = somaX + Math.cos(a) * (somaR + len);
      const y2  = somaY + Math.sin(a) * (somaR + len);
      append(svg, Shapes.line(x1, y1, x2, y2, { color: 'secondary', sw: 1.8 }));
      append(svg, Shapes.circle(x2, y2, 3, { fill: 'secondary', color: 'secondary' }));
    });

    // Soma (drawn after dendrites so it overlaps their roots)
    const soma = Shapes.circle(somaX, somaY, somaR,
      { color: 'secondary', fill: 'secondary', sw: 2 });
    soma.setAttribute('fill-opacity', '0.3');
    append(svg, soma);
    engine.addMotion(Motion.pulse(soma, somaX, somaY, 0.94, 1.06, 2000));
    append(svg, Shapes.text(somaX, somaY, 'soma', { color: 'secondary', size: 8 }));

    // Axon
    const axonX1 = somaX + somaR, axonX2 = W - 22, axonY = somaY;
    append(svg, Shapes.line(axonX1, axonY, axonX2, axonY, { color: 'green', sw: 2.5 }));
    append(svg, Shapes.text((axonX1 + axonX2) / 2, axonY - 10, 'axon',
      { color: 'green', size: 8 }));

    // Myelin sheaths
    const mSpan = axonX2 - axonX1 - 30, mN = 4;
    for (let i = 0; i < mN; i++) {
      const mx = axonX1 + 15 + (i / mN) * mSpan + mSpan / (2 * mN);
      append(svg, Shapes.ellipse(mx, axonY, 22, 8,
        { color: 'orange', fill: 'orange', sw: 0, opacity: 0.45 }));
    }

    // Action potential particle
    const signal = Shapes.circle(axonX1, axonY, 6,
      { fill: 'yellow', color: 'yellow', glow: true });
    append(svg, signal);
    engine.addMotion(Motion.linearPath(
      signal, axonX1, axonY, axonX2, axonY, 2200, { useCxy: true }));

    // Terminal button
    const terminal = Shapes.circle(axonX2, axonY, 9,
      { fill: 'coral', color: 'coral', glow: true });
    append(svg, terminal);
    engine.addMotion(Motion.pulse(terminal, axonX2, axonY, 0.8, 1.2, 1500));
    append(svg, Shapes.text(axonX2, axonY + 20, 'terminal',
      { color: 'orange', size: 7 }));

    if (label) {
      append(svg, Shapes.text(CX, 14, label,
        { color: 'label', size: 13, weight: 'bold' }));
    }
  }

  // ── pendulum ─────────────────────────────────────────────────────────────────
  
  function pendulum(engine, svg, data) {
    const label    = data.label  || 'Pendulum';
    const maxAngle = (data.angle || 35) * Math.PI / 180;
    const periodMs = (data.period || 2) * 1000;
    const pivotX   = CX, pivotY = 28;
    const rodLen   = 145, bobR = 14;

    // Ceiling support
    append(svg, Shapes.line(pivotX - 32, pivotY - 2, pivotX + 32, pivotY - 2,
      { color: 'dim', sw: 5 }));
    for (let i = 0; i < 5; i++) {
      append(svg, Shapes.line(pivotX - 24 + i * 12, pivotY - 2,
        pivotX - 30 + i * 12, pivotY + 8, { color: 'dim', sw: 1 }));
    }

    // Swing arc (dashed)
    const lx = pivotX + Math.sin(-maxAngle) * rodLen;
    const ly = pivotY + Math.cos(maxAngle)  * rodLen;
    const rx = pivotX + Math.sin( maxAngle) * rodLen;
    append(svg, Shapes.path(
      `M ${lx.toFixed(1)} ${ly.toFixed(1)} A ${rodLen} ${rodLen} 0 0 1 ${rx.toFixed(1)} ${ly.toFixed(1)}`,
      { color: 'dim', sw: 0.6, dash: '5 4' }));

    // Equilibrium dotted line
    append(svg, Shapes.dottedLine(pivotX, pivotY, pivotX, pivotY + rodLen + 12,
      { color: 'dim' }));

    // Rod + bob (positions updated every frame)
    const rod = Shapes.line(pivotX, pivotY, pivotX, pivotY + rodLen,
      { color: 'stroke', sw: 2 });
    const bob = Shapes.circle(pivotX, pivotY + rodLen, bobR,
      { color: 'orange', fill: 'orange', glow: true });
    append(svg, rod, bob);

    // Pivot pin
    append(svg, Shapes.circle(pivotX, pivotY, 4, { fill: 'dim', color: 'dim' }));

    engine.addMotion({
      update(elapsed) {
        const angle = maxAngle * Math.cos((elapsed / periodMs) * 2 * Math.PI);
        const bx = pivotX + Math.sin(angle) * rodLen;
        const by = pivotY + Math.cos(angle) * rodLen;
        rod.setAttribute('x2', bx.toFixed(1));
        rod.setAttribute('y2', by.toFixed(1));
        bob.setAttribute('cx', bx.toFixed(1));
        bob.setAttribute('cy', by.toFixed(1));
      },
    });

    append(svg, Shapes.text(pivotX + 9, pivotY + rodLen / 2, 'L',
      { color: 'label', size: 12 }));
    append(svg, Shapes.text(pivotX - 42, pivotY + 22, `θ=${data.angle || 35}°`,
      { color: 'orange', size: 9 }));
    if (label) {
      append(svg, Shapes.text(CX, H - 10, label,
        { color: 'label', size: 12, weight: 'bold' }));
    }
  }

  // ── springMass ───────────────────────────────────────────────────────────────
  
  function springMass(engine, svg, data) {
    const label    = data.label  || 'Spring–Mass';
    const periodMs = (data.period || 2) * 1000;
    const wallX    = 28, springY = CY;
    const restX    = 230, amp = 65;
    const massW    = 52, massH = 52;

    // Wall
    append(svg, Shapes.line(wallX, springY - 62, wallX, springY + 62,
      { color: 'dim', sw: 5 }));
    for (let i = 0; i < 7; i++) {
      append(svg, Shapes.line(wallX - 10, springY - 56 + i * 19,
        wallX, springY - 47 + i * 19, { color: 'dim', sw: 1 }));
    }

    // Floor
    append(svg, Shapes.line(wallX, springY + 40, W - 14, springY + 40,
      { color: 'dim', sw: 1.5 }));

    // Equilibrium marker
    append(svg, Shapes.dottedLine(restX, springY - 44, restX, springY + 42,
      { color: 'label', sw: 0.8 }));
    append(svg, Shapes.text(restX, springY - 49, 'eq', { color: 'dim', size: 8 }));

    // Spring (path rebuilt each frame) + mass + label — all updated via motion
    const springEl = Shapes.path('', { color: 'green', sw: 2 });
    const massEl   = Shapes.roundedRect(restX - massW / 2, springY - massH / 2,
      massW, massH, { color: 'secondary', fill: 'bg', sw: 2 });
    const massLbl  = Shapes.text(restX, springY, 'm',
      { color: 'secondary', size: 18, weight: 'bold' });
    append(svg, springEl, massEl, massLbl);

    engine.addMotion({
      update(elapsed) {
        const massX = restX + amp * Math.cos((elapsed / periodMs) * 2 * Math.PI);
        // Move mass
        massEl.setAttribute('x', massX - massW / 2);
        massLbl.setAttribute('x', massX);
        // Redraw spring zigzag
        const x0 = wallX + 3, x1 = massX - massW / 2;
        const N = 8, dw = 8, a = 12;
        const coilW = (x1 - x0 - 2 * dw) / N;
        let d = `M ${x0} ${springY} L ${(x0 + dw).toFixed(1)} ${springY}`;
        for (let i = 0; i < N; i++) {
          const xp = (x0 + dw + (i + 0.5) * coilW).toFixed(1);
          const yp = springY + (i % 2 === 0 ? -a : a);
          d += ` L ${xp} ${yp}`;
        }
        d += ` L ${x1.toFixed(1)} ${springY}`;
        springEl.setAttribute('d', d);
      },
    });

    append(svg, Shapes.text(wallX + 14, springY - 52, 'k',
      { color: 'green', size: 11, anchor: 'start' }));
    if (label) {
      append(svg, Shapes.text(CX, 16, label,
        { color: 'label', size: 13, weight: 'bold' }));
    }
  }

  // ── dna ──────────────────────────────────────────────────────────────────────
  
  function dna(engine, svg, data) {
    const label    = data.label || 'DNA Double Helix';
    const x0 = 35, x1 = W - 35, cy = CY, amp = 52;
    const scrollMs = 3000;
    const PAIRS = 9, BEADS = 18;

    // Base-pair connectors (colours: A-T orange, G-C green, else purple)
    const connectors = [];
    for (let i = 0; i < PAIRS; i++) {
      const col = i % 3 === 0 ? resolveColor('orange')
                : i % 3 === 1 ? resolveColor('green')
                :               resolveColor('purple');
      const conn = svgEl('line', {
        x1: 0, y1: 0, x2: 0, y2: 0,
        stroke: col, 'stroke-width': 2, 'stroke-dasharray': '3 2',
      });
      append(svg, conn);
      connectors.push(conn);
    }

    // Strand paths
    const strand1 = Shapes.path('', { color: 'secondary', sw: 2.5 });
    const strand2 = Shapes.path('', { color: 'highlight', sw: 2.5 });
    append(svg, strand1, strand2);

    // Nucleotide beads on each strand
    const beads1 = [], beads2 = [];
    for (let i = 0; i < BEADS; i++) {
      const b1 = Shapes.circle(0, 0, 3, { fill: 'secondary', color: 'secondary' });
      const b2 = Shapes.circle(0, 0, 3, { fill: 'highlight', color: 'highlight' });
      append(svg, b1, b2);
      beads1.push(b1); beads2.push(b2);
    }

    engine.addMotion({
      update(elapsed) {
        const phase = (elapsed / scrollMs) * 2 * Math.PI;
        const steps = 80;
        const p1 = [], p2 = [];
        for (let i = 0; i <= steps; i++) {
          const x  = x0 + (i / steps) * (x1 - x0);
          const tw = (x / (x1 - x0)) * 2.5 * 2 * Math.PI - phase;
          const y1v = cy + amp * Math.sin(tw);
          const y2v = cy + amp * Math.sin(tw + Math.PI);
          p1.push(i === 0 ? `M ${x.toFixed(1)} ${y1v.toFixed(1)}`
                          : `L ${x.toFixed(1)} ${y1v.toFixed(1)}`);
          p2.push(i === 0 ? `M ${x.toFixed(1)} ${y2v.toFixed(1)}`
                          : `L ${x.toFixed(1)} ${y2v.toFixed(1)}`);
        }
        strand1.setAttribute('d', p1.join(' '));
        strand2.setAttribute('d', p2.join(' '));

        // Connectors
        for (let i = 0; i < PAIRS; i++) {
          const x  = x0 + ((i + 0.5) / PAIRS) * (x1 - x0);
          const tw = (x / (x1 - x0)) * 2.5 * 2 * Math.PI - phase;
          const y1v = cy + amp * Math.sin(tw);
          const y2v = cy + amp * Math.sin(tw + Math.PI);
          connectors[i].setAttribute('x1', x.toFixed(1));
          connectors[i].setAttribute('y1', y1v.toFixed(1));
          connectors[i].setAttribute('x2', x.toFixed(1));
          connectors[i].setAttribute('y2', y2v.toFixed(1));
        }

        // Beads
        for (let i = 0; i < BEADS; i++) {
          const x  = x0 + ((i + 0.5) / BEADS) * (x1 - x0);
          const tw = (x / (x1 - x0)) * 2.5 * 2 * Math.PI - phase;
          beads1[i].setAttribute('cx', x.toFixed(1));
          beads1[i].setAttribute('cy', (cy + amp * Math.sin(tw)).toFixed(1));
          beads2[i].setAttribute('cx', x.toFixed(1));
          beads2[i].setAttribute('cy', (cy + amp * Math.sin(tw + Math.PI)).toFixed(1));
        }
      },
    });

    append(svg, Shapes.text(x0, cy - amp - 10, "5'→3'",
      { color: 'secondary', size: 9, anchor: 'start' }));
    append(svg, Shapes.text(x0, cy + amp + 18, "3'→5'",
      { color: 'highlight', size: 9, anchor: 'start' }));
    if (label) {
      append(svg, Shapes.text(CX, 14, label,
        { color: 'label', size: 13, weight: 'bold' }));
    }
  }

  // ── lens (convex lens ray diagram) ───────────────────────────────────────────
  
  function lens(engine, svg, data) {
    const label = data.label || 'Convex Lens';
    const fLen  = data.focalLength || 80;
    const lensX = CX + 10, lensY = CY + 5;
    const objX  = lensX - 138, objH = 56;
    const lH    = 70;

    // Principal axis
    append(svg, Shapes.arrow(16, lensY, W - 16, lensY, { color: 'dim', sw: 1 }));

    // Biconvex lens shape
    const lensPath = (
      `M ${lensX} ${lensY - lH}` +
      ` Q ${lensX + 24} ${lensY} ${lensX} ${lensY + lH}` +
      ` Q ${lensX - 24} ${lensY} ${lensX} ${lensY - lH} Z`
    );
    const lensEl = Shapes.path(lensPath, { color: 'secondary', fill: 'secondary', sw: 1.5 });
    lensEl.setAttribute('fill-opacity', '0.12');
    append(svg, lensEl);

    // Focal points
    append(svg, Shapes.circle(lensX - fLen, lensY, 4, { fill: 'label', color: 'label' }));
    append(svg, Shapes.circle(lensX + fLen, lensY, 4, { fill: 'label', color: 'label' }));
    append(svg, Shapes.text(lensX - fLen, lensY + 13, 'F', { color: 'label', size: 9 }));
    append(svg, Shapes.text(lensX + fLen, lensY + 13, 'F', { color: 'label', size: 9 }));

    // Object arrow
    append(svg, Shapes.arrow(objX, lensY, objX, lensY - objH, { color: 'green', sw: 2.5 }));
    append(svg, Shapes.text(objX, lensY + 12, 'Object', { color: 'green', size: 8 }));

    // Thin-lens formula: 1/v = 1/f + 1/u  (u < 0)
    const u = objX - lensX;
    const vDenom = (1 / fLen) + (1 / u);
    const v = Math.abs(vDenom) > 0.001 ? 1 / vDenom : 999;
    const mag = -v / u;
    const imgX = lensX + v;
    const imgH = objH * Math.abs(mag);
    const imgDir = mag < 0 ? 1 : -1;  // real image inverted

    // Image arrow (pulsing opacity)
    const imgEl = Shapes.arrow(imgX, lensY, imgX, lensY + imgDir * imgH,
      { color: 'highlight', sw: 2 });
    append(svg, imgEl);
    engine.addMotion(Motion.fade(imgEl, 0.35, 1.0, 2200));
    append(svg, Shapes.text(imgX, lensY + 12, 'Image', { color: 'highlight', size: 8 }));

    // Three principal rays (staggered fade pulses for teaching clarity)
    const objTipY = lensY - objH;
    const imgTipY = lensY + imgDir * imgH;

    // Ray 1: parallel to axis → through far focal point
    const ray1 = Shapes.path(
      `M ${objX} ${objTipY} L ${lensX} ${objTipY} L ${imgX} ${imgTipY}`,
      { color: 'orange', sw: 1, dash: '5 3' });
    append(svg, ray1);
    engine.addMotion(Motion.fade(ray1, 0.2, 0.9, 2800, { phase: 0 }));

    // Ray 2: through optical centre (straight)
    append(svg, Shapes.line(objX, objTipY, imgX, imgTipY,
      { color: 'teal', sw: 1, dash: '5 3' }));
    engine.addMotion(Motion.fade(svg.lastElementChild, 0.2, 0.9, 2800, { phase: 0.33 }));

    // Ray 3: through near focal point → exits parallel
    const r3HitY = lensY + (objTipY - lensY) * lensX / (lensX - (lensX - fLen));
    const ray3 = Shapes.path(
      `M ${objX} ${objTipY} L ${lensX} ${r3HitY.toFixed(1)} L ${imgX} ${imgTipY}`,
      { color: 'pink', sw: 1, dash: '5 3' });
    append(svg, ray3);
    engine.addMotion(Motion.fade(ray3, 0.2, 0.9, 2800, { phase: 0.66 }));

    if (label) {
      append(svg, Shapes.text(CX, 14, label,
        { color: 'label', size: 13, weight: 'bold' }));
    }
  }

  // ── electricField ─────────────────────────────────────────────────────────────
  
  function electricField(engine, svg, data) {
    const label    = data.label || 'Electric Field';
    const c1x = CX - 105, c1y = CY;
    const c2x = CX + 105, c2y = CY;
    const cR = 18, scrollMs = 3200;

    // Field line paths (bezier arcs from + to −)
    const fieldDefs = [
      `M ${c1x + cR} ${c1y} L ${c2x - cR} ${c2y}`,
      `M ${c1x + cR} ${c1y - 6} Q ${CX} ${c1y - 72} ${c2x - cR} ${c2y - 6}`,
      `M ${c1x + cR} ${c1y + 6} Q ${CX} ${c1y + 72} ${c2x - cR} ${c2y + 6}`,
      `M ${c1x + cR} ${c1y - 14} Q ${CX} ${c1y - 118} ${c2x - cR} ${c2y - 14}`,
      `M ${c1x + cR} ${c1y + 14} Q ${CX} ${c1y + 118} ${c2x - cR} ${c2y + 14}`,
    ];
    const lineEls = fieldDefs.map(d => {
      const el = Shapes.path(d, { color: 'label', sw: 1 });
      append(svg, el);
      return el;
    });

    // Animated particles along each field line
    const particles = lineEls.map((el, i) => {
      const dot = Shapes.circle(0, 0, 3.5, { fill: 'orange', color: 'orange', glow: true });
      append(svg, dot);
      return dot;
    });

    engine.addMotion({
      update(elapsed) {
        lineEls.forEach((el, i) => {
          try {
            const len = el.getTotalLength();
            const t   = (elapsed / scrollMs + i * 0.2) % 1;
            const pt  = el.getPointAtLength(t * len);
            particles[i].setAttribute('cx', pt.x.toFixed(1));
            particles[i].setAttribute('cy', pt.y.toFixed(1));
          } catch (e) {  }
        });
      },
    });

    // Charges (drawn on top)
    const posEl = Shapes.circle(c1x, c1y, cR, { color: 'highlight', fill: 'highlight', glow: true });
    posEl.setAttribute('fill-opacity', '0.7');
    append(svg, posEl);
    engine.addMotion(Motion.pulse(posEl, c1x, c1y, 0.90, 1.10, 1600));

    const negEl = Shapes.circle(c2x, c2y, cR, { color: 'secondary', fill: 'secondary', glow: true });
    negEl.setAttribute('fill-opacity', '0.7');
    append(svg, negEl);
    engine.addMotion(Motion.pulse(negEl, c2x, c2y, 0.90, 1.10, 1800));

    append(svg, Shapes.text(c1x, c1y + 1, '+',
      { color: 'white', size: 22, weight: 'bold' }));
    append(svg, Shapes.text(c2x, c2y + 1, '−',
      { color: 'white', size: 22, weight: 'bold' }));
    if (label) {
      append(svg, Shapes.text(CX, 14, label,
        { color: 'label', size: 13, weight: 'bold' }));
    }
  }

  // ════════════════════════════════════════════════════════════════════════════
  // MATHEMATICS RENDERERS
  // ════════════════════════════════════════════════════════════════════════════

  // ── numberLine ───────────────────────────────────────────────────────────────
  // data: { start, end, marked_points, highlight_range, label }
  function numberLine(engine, svg, data) {
    var start  = data.start != null ? +data.start : -5;
    var end    = data.end   != null ? +data.end   :  5;
    if (end <= start) end = start + 10;
    var title  = data.label || data.title || 'Number Line';
    var marks  = (data.marked_points || []).map(Number);
    var hiRng  = data.highlight_range;

    var OX = 30, OY = 160, W2 = 340;
    var scale = W2 / (end - start);
    function tx(v) { return OX + (v - start) * scale; }

    append(svg, Shapes.text(200, 22, title, { color: 'label', size: 13, weight: 'bold' }));

    if (hiRng && hiRng.length >= 2) {
      var lx = tx(+hiRng[0]), rx2 = tx(+hiRng[1]);
      var band = svgEl('rect', { x: lx, y: OY - 14, width: Math.max(rx2 - lx, 4), height: 28,
        fill: resolveColor('secondary'), opacity: 0.25, rx: 4 });
      append(svg, band);
    }

    append(svg, Shapes.arrow(OX - 12, OY, OX + W2 + 16, OY, { color: 'stroke', sw: 2 }));

    var step = 1;
    if (end - start > 20) step = 5;
    else if (end - start > 12) step = 2;
    var first = Math.ceil(start / step) * step;
    for (var v = first; v <= end; v += step) {
      var x = tx(v);
      append(svg, Shapes.line(x, OY - 7, x, OY + 7, { color: 'stroke', sw: 1.2 }));
      append(svg, Shapes.text(x, OY + 22, String(v), { color: 'dim', size: 10 }));
    }

    var staggerMs = 600;
    marks.forEach(function(m, i) {
      var x = tx(m);
      var dot = Shapes.circle(x, OY, 6, { color: 'highlight', fill: 'highlight', opacity: 0 });
      var lbl = Shapes.text(x, OY - 20, String(m), { color: 'highlight', size: 11, weight: 'bold', opacity: 0 });
      append(svg, dot, lbl);
      engine.addPhase([dot, lbl], staggerMs + i * 400, -1);
    });

    var dot0 = Shapes.circle(tx(0), OY, 4, { color: 'secondary', fill: 'secondary', opacity: 0 });
    append(svg, dot0);
    engine.addPhase([dot0], 300, -1);
  }

  // ── fractionBar ──────────────────────────────────────────────────────────────
  // data: { fractions:[{num,den,label?}], title }
  function fractionBar(engine, svg, data) {
    var fracs = (data.fractions || [{ num: 1, den: 2 }, { num: 3, den: 4 }]).slice(0, 5);
    var title = data.title || 'Fraction Comparison';
    append(svg, Shapes.text(200, 22, title, { color: 'label', size: 13, weight: 'bold' }));

    var barW = 300, barH = 28, startX = 50, startY = 50;
    var colors = ['secondary', 'highlight', 'green', 'orange', 'purple'];

    fracs.forEach(function(f, i) {
      var num = +f.num, den = +f.den || 1;
      var ratio = Math.min(num / den, 1);
      var y = startY + i * (barH + 20);
      var color = colors[i % colors.length];

      var bg = Shapes.rect(startX, y, barW, barH,
        { color: 'dim', fill: 'bg', sw: 1, rx: 3 });
      var fill2 = svgEl('rect', {
        x: startX, y: y, width: 0, height: barH,
        fill: resolveColor(color), rx: 3, opacity: 0.85,
      });
      var fracLbl = Shapes.text(startX - 8, y + barH / 2, num + '/' + den,
        { color: color, size: 12, weight: 'bold', anchor: 'end' });
      var pctLbl = Shapes.text(startX + barW + 8, y + barH / 2,
        Math.round(ratio * 100) + '%', { color: color, size: 10, anchor: 'start' });

      for (var d = 1; d < den; d++) {
        var tx2 = startX + (d / den) * barW;
        append(svg, Shapes.line(tx2, y, tx2, y + barH,
          { color: 'bg', sw: 1.5 }));
      }

      append(svg, bg, fill2, fracLbl, pctLbl);
      engine.addPhase([bg, fracLbl, pctLbl], i * 500, -1);

      var targetW = ratio * barW;
      engine.addMotion({
        update: function(w, f2, s) {
          return function(elapsed) {
            var prog = Math.min(elapsed / (s + 800), 1);
            prog = prog < 1 ? 3 * prog * prog - 2 * prog * prog * prog : 1;
            f2.setAttribute('width', w * prog);
            f2.setAttribute('opacity', elapsed > s ? 0.85 : 0);
          };
        }(targetW, fill2, i * 500)
      });
    });
  }

  // ── graphFunction ─────────────────────────────────────────────────────────────
  // data: { function:'quadratic'|'linear'|'cubic'|'sine'|'cosine'|'abs',
  //         a, b, c, x_range, label, color, show_axes }
  function graphFunction(engine, svg, data) {
    var fnType   = (data['function'] || 'quadratic').toLowerCase();
    var a        = data.a != null ? +data.a : 1;
    var b        = data.b != null ? +data.b : 0;
    var c2       = data.c != null ? +data.c : 0;
    var xRange   = data.x_range || [-4, 4];
    var label    = data.label || data.title || '';
    var color    = data.color || 'secondary';
    var x0       = +xRange[0], x1 = +xRange[1];

    var OX = 200, OY = 150;
    var xScale = 280 / Math.max(Math.abs(x1 - x0), 0.1);
    var yScale = 100;

    function toSvgX(x) { return OX + x * xScale; }
    function toSvgY(y) { return OY - Math.max(Math.min(y * yScale, 140), -140); }

    function evalFn(x) {
      switch (fnType) {
        case 'linear':   return a * x + b;
        case 'cubic':    return a * x * x * x + b * x + c2;
        case 'sine':     return a * Math.sin(b * x + c2);
        case 'cosine':   return a * Math.cos(b * x + c2);
        case 'abs':      return a * Math.abs(x) + b;
        case 'sqrt':     return x >= 0 ? a * Math.sqrt(x) + b : null;
        default:         return a * x * x + b * x + c2;
      }
    }

    append(svg, Shapes.rect(30, 10, 340, 280, { color: 'bg', fill: 'bg', sw: 0 }));

    // Grid
    for (var gx = Math.ceil(x0); gx <= x1; gx++) {
      append(svg, Shapes.line(toSvgX(gx), 20, toSvgX(gx), 280,
        { color: 'bg', sw: 0.5, dash: '2 3' }));
    }
    for (var gy = -3; gy <= 3; gy++) {
      append(svg, Shapes.line(30, toSvgY(gy), 370, toSvgY(gy),
        { color: 'dim', sw: 0.4, dash: '2 3' }));
    }

    // Axes
    append(svg, Shapes.arrow(30, OY, 374, OY, { color: 'stroke', sw: 1.5 }));
    append(svg, Shapes.arrow(OX, 280, OX, 14, { color: 'stroke', sw: 1.5 }));
    append(svg, Shapes.text(378, OY, 'x', { color: 'dim', size: 11 }));
    append(svg, Shapes.text(OX, 9,   'y', { color: 'dim', size: 11 }));

    // Axis ticks
    for (var tx2 = Math.ceil(x0); tx2 <= x1; tx2++) {
      if (tx2 === 0) continue;
      append(svg, Shapes.line(toSvgX(tx2), OY - 5, toSvgX(tx2), OY + 5, { color: 'dim', sw: 1 }));
      append(svg, Shapes.text(toSvgX(tx2), OY + 15, String(tx2), { color: 'dim', size: 9 }));
    }

    // Build curve path
    var steps = 120;
    var pts = [];
    for (var i = 0; i <= steps; i++) {
      var xv = x0 + (i / steps) * (x1 - x0);
      var yv = evalFn(xv);
      if (yv == null || !isFinite(yv)) continue;
      pts.push((i === 0 ? 'M' : 'L') + ' ' + toSvgX(xv).toFixed(1) + ' ' + toSvgY(yv).toFixed(1));
    }

    var curvePath = Shapes.path(pts.join(' '), { color: color, sw: 2.5 });
    curvePath.setAttribute('opacity', 0);
    var totalLen = 0;
    append(svg, curvePath);
    engine.addPhase([curvePath], 300, -1);

    // Animated draw-on using stroke-dashoffset
    engine.addMotion({
      update: function(el) {
        var init = false;
        return function(elapsed) {
          if (!init) {
            try {
              totalLen = el.getTotalLength();
              el.setAttribute('stroke-dasharray', totalLen);
              el.setAttribute('stroke-dashoffset', totalLen);
            } catch(e) {}
            init = true;
          }
          var prog = Math.min((elapsed - 300) / 1800, 1);
          if (prog <= 0) return;
          el.setAttribute('opacity', 1);
          var offset = totalLen * (1 - (prog < 1 ? 3*prog*prog - 2*prog*prog*prog : 1));
          el.setAttribute('stroke-dashoffset', offset);
        };
      }(curvePath)
    });

    // Animated dot travelling along curve
    var dot = Shapes.circle(0, 0, 5, { color: color, fill: color, glow: true, opacity: 0 });
    append(svg, dot);
    engine.addMotion({
      update: function(el, pathEl) {
        return function(elapsed) {
          if (elapsed < 2200) { el.setAttribute('opacity', 0); return; }
          try {
            var len = pathEl.getTotalLength();
            var t = ((elapsed - 2200) / 3000) % 1;
            var pt = pathEl.getPointAtLength(t * len);
            el.setAttribute('cx', pt.x);
            el.setAttribute('cy', pt.y);
            el.setAttribute('opacity', 1);
          } catch(e) {}
        };
      }(dot, curvePath)
    });

    if (label) {
      append(svg, Shapes.text(200, 15, label,
        { color: 'label', size: 12, weight: 'bold' }));
    }
  }

  // ── triangle ──────────────────────────────────────────────────────────────────
  // data: { labels, sides, angles, show_height, show_incircle,
  //         show_circumcircle, show_median, show_angles, type,
  //         a_val, b_val, c_val, title }
  function triangle(engine, svg, data) {
    var labels  = data.labels || ['A', 'B', 'C'];
    var ttype   = (data.type || 'equilateral').toLowerCase();
    var title   = data.title || labels[0] + labels[1] + labels[2] + ' Triangle';

    // Vertex positions — different shapes
    var A, B, C;
    if (ttype === 'right') {
      A = [200, 60]; B = [90, 240]; C = [200, 240];
    } else if (ttype === 'obtuse') {
      A = [200, 65]; B = [60, 240]; C = [320, 240];
    } else if (ttype === 'isosceles') {
      A = [200, 60]; B = [110, 240]; C = [290, 240];
    } else if (ttype === 'scalene') {
      A = [170, 65]; B = [80, 240]; C = [320, 240];
    } else {
      A = [200, 55]; B = [90, 240]; C = [310, 240];
    }

    function dist(P, Q) {
      return Math.sqrt((Q[0]-P[0])*(Q[0]-P[0]) + (Q[1]-P[1])*(Q[1]-P[1]));
    }
    var sA = dist(B, C), sB = dist(A, C), sC = dist(A, B);
    var perim = sA + sB + sC;
    var area2 = Math.abs((B[0]-A[0])*(C[1]-A[1]) - (C[0]-A[0])*(B[1]-A[1]));
    var area  = area2 / 2;

    // Helper: line element
    function ln(P, Q, opts) { return Shapes.line(P[0], P[1], Q[0], Q[1], opts || {}); }

    append(svg, Shapes.text(200, 20, title,
      { color: 'label', size: 12, weight: 'bold' }));

    // Sides
    var sideAB = ln(A, B, { color: 'highlight', sw: 2 });
    var sideBC = ln(B, C, { color: 'highlight', sw: 2 });
    var sideCA = ln(C, A, { color: 'highlight', sw: 2 });
    sideAB.setAttribute('opacity', 0);
    sideBC.setAttribute('opacity', 0);
    sideCA.setAttribute('opacity', 0);
    append(svg, sideAB, sideBC, sideCA);
    engine.addPhase([sideAB], 0,   -1);
    engine.addPhase([sideBC], 300, -1);
    engine.addPhase([sideCA], 600, -1);

    // Vertex dots
    [A, B, C].forEach(function(P, i) {
      var d = Shapes.circle(P[0], P[1], 4, { color: 'label', fill: 'label', opacity: 0 });
      var t = Shapes.text(
        P[0] + (i === 0 ? 0 : i === 1 ? -18 : 18),
        P[1] + (i === 0 ? -16 : 18),
        labels[i] || '',
        { color: 'label', size: 14, weight: 'bold', opacity: 0 }
      );
      append(svg, d, t);
      engine.addPhase([d, t], 800 + i * 200, -1);
    });

    // Side length labels
    if (data.a_val || data.b_val || data.c_val) {
      var mBC = [(B[0]+C[0])/2, (B[1]+C[1])/2 + 16];
      var mCA = [(C[0]+A[0])/2 + 16, (C[1]+A[1])/2];
      var mAB = [(A[0]+B[0])/2 - 16, (A[1]+B[1])/2];
      [[data.a_val, mBC], [data.b_val, mCA], [data.c_val, mAB]].forEach(function(pair, i) {
        if (!pair[0]) return;
        var sl = Shapes.text(pair[1][0], pair[1][1], String(pair[0]),
          { color: 'secondary', size: 11, weight: 'bold', opacity: 0 });
        append(svg, sl);
        engine.addPhase([sl], 1200 + i * 200, -1);
      });
    }

    // Show angles arc + label
    if (data.show_angles || data.angles) {
      var angData = data.angles || {};
      [[A, B, C, 'a'], [B, A, C, 'b'], [C, A, B, 'c']].forEach(function(quad, i) {
        var P = quad[0], O = quad[1], Q = quad[2];
        var av = angData[quad[3]] || '';
        var r  = 22;
        var a1 = Math.atan2(P[1] - O[1], P[0] - O[0]) * 180 / Math.PI;
        var a2 = Math.atan2(Q[1] - O[1], Q[0] - O[0]) * 180 / Math.PI;
        var arcEl = Shapes.arc(O[0], O[1], r, a1, a2, { color: 'orange', sw: 1.5, opacity: 0 });
        append(svg, arcEl);
        engine.addPhase([arcEl], 1500 + i * 200, -1);
        if (av) {
          var mid = (a1 + a2) / 2 * Math.PI / 180;
          var lx = O[0] + (r + 12) * Math.cos(mid);
          var ly = O[1] + (r + 12) * Math.sin(mid);
          var al = Shapes.text(lx, ly, av + '°',
            { color: 'orange', size: 10, weight: 'bold', opacity: 0 });
          append(svg, al);
          engine.addPhase([al], 1700 + i * 200, -1);
        }
      });
    }

    // Height (altitude from A to BC)
    if (data.show_height) {
      var BCdx = C[0] - B[0], BCdy = C[1] - B[1];
      var t2 = ((A[0]-B[0])*BCdx + (A[1]-B[1])*BCdy) / (BCdx*BCdx + BCdy*BCdy);
      var foot = [B[0] + t2*BCdx, B[1] + t2*BCdy];
      var hl = Shapes.line(A[0], A[1], foot[0], foot[1],
        { color: 'secondary', sw: 1.5, dash: '5 3', opacity: 0 });
      var sq1 = Shapes.rect(foot[0] - 5, foot[1] - 5, 10, 10,
        { color: 'secondary', sw: 1, opacity: 0 });
      var ht = Shapes.text((A[0]+foot[0])/2 - 14, (A[1]+foot[1])/2,
        'h', { color: 'secondary', size: 11, weight: 'bold', opacity: 0 });
      append(svg, hl, sq1, ht);
      engine.addPhase([hl, sq1, ht], 2000, -1);
    }

    // Incircle
    if (data.show_incircle) {
      var ir = area / (perim / 2);
      var ix = (sA*A[0] + sB*B[0] + sC*C[0]) / perim;
      var iy = (sA*A[1] + sB*B[1] + sC*C[1]) / perim;
      var ic = Shapes.circle(ix, iy, ir,
        { color: 'orange', sw: 1.5, dash: '4 3', opacity: 0 });
      var il = Shapes.text(ix, iy, 'r=' + ir.toFixed(1),
        { color: 'orange', size: 9, opacity: 0 });
      append(svg, ic, il);
      engine.addPhase([ic, il], 2200, -1);
    }

    // Circumcircle
    if (data.show_circumcircle) {
      var R2 = (sA * sB * sC) / (4 * area);
      var D2 = 2 * (A[0]*(B[1]-C[1]) + B[0]*(C[1]-A[1]) + C[0]*(A[1]-B[1]));
      if (Math.abs(D2) > 0.001) {
        var ux = ((A[0]*A[0]+A[1]*A[1])*(B[1]-C[1]) +
                  (B[0]*B[0]+B[1]*B[1])*(C[1]-A[1]) +
                  (C[0]*C[0]+C[1]*C[1])*(A[1]-B[1])) / D2;
        var uy = ((A[0]*A[0]+A[1]*A[1])*(C[0]-B[0]) +
                  (B[0]*B[0]+B[1]*B[1])*(A[0]-C[0]) +
                  (C[0]*C[0]+C[1]*C[1])*(B[0]-A[0])) / D2;
        var cc = Shapes.circle(ux, uy, R2,
          { color: 'purple', sw: 1.5, dash: '5 3', opacity: 0 });
        var cl = Shapes.text(ux, uy + R2 + 12, 'R=' + R2.toFixed(1),
          { color: 'purple', size: 9, opacity: 0 });
        append(svg, cc, cl);
        engine.addPhase([cc, cl], 2500, -1);
      }
    }

    // Median from A to midpoint of BC
    if (data.show_median) {
      var mBC2 = [(B[0]+C[0])/2, (B[1]+C[1])/2];
      var ml = Shapes.line(A[0], A[1], mBC2[0], mBC2[1],
        { color: 'green', sw: 1.5, dash: '4 3', opacity: 0 });
      var md = Shapes.circle(mBC2[0], mBC2[1], 4,
        { color: 'green', fill: 'green', opacity: 0 });
      append(svg, ml, md);
      engine.addPhase([ml, md], 2800, -1);
    }

    // Right-angle marker
    if (ttype === 'right') {
      var sq2 = Shapes.rect(C[0] - 12, C[1] - 12, 12, 12,
        { color: 'stroke', sw: 1.2, opacity: 0 });
      append(svg, sq2);
      engine.addPhase([sq2], 700, -1);
    }

    // Pythagoras label (only for right triangle)
    if (ttype === 'right' && data.show_pythagoras) {
      var hyp = dist(A, B);
      var leg1 = dist(B, C), leg2 = dist(A, C);
      var pl = Shapes.text(200, 280,
        leg1.toFixed(0) + '² + ' + leg2.toFixed(0) + '² = ' + hyp.toFixed(0) + '²',
        { color: 'gold', size: 11, weight: 'bold', opacity: 0 });
      append(svg, pl);
      engine.addPhase([pl], 3200, -1);
    }
  }

  // ── polygon ──────────────────────────────────────────────────────────────────
  // data: { sides, radius, label, show_diagonals, show_angles,
  //         show_incircle, show_circumcircle, color }
  function regularPolygon(engine, svg, data) {
    var n      = Math.max(3, Math.min(12, +data.sides || 6));
    var R      = Math.min(+data.radius || 100, 110);
    var color  = data.color || 'secondary';
    var label2 = data.label || data.title || _polyName(n);
    var cx = 200, cy = 155;

    var pts = [];
    for (var i = 0; i < n; i++) {
      var ang = (i / n) * 2 * Math.PI - Math.PI / 2;
      pts.push([cx + R * Math.cos(ang), cy + R * Math.sin(ang)]);
    }

    append(svg, Shapes.text(200, 20, label2,
      { color: 'label', size: 13, weight: 'bold' }));

    // Sides (animated one by one)
    pts.forEach(function(P, i) {
      var Q = pts[(i + 1) % n];
      var sl = Shapes.line(P[0], P[1], Q[0], Q[1],
        { color: color, sw: 2, opacity: 0 });
      append(svg, sl);
      engine.addPhase([sl], i * (1000 / n), -1);
    });

    // Vertex dots
    pts.forEach(function(P, i) {
      var d = Shapes.circle(P[0], P[1], 4,
        { color: 'label', fill: 'label', opacity: 0 });
      append(svg, d);
      engine.addPhase([d], 1200 + i * 100, -1);
    });

    // Interior angles
    if (data.show_angles) {
      var intAngle = ((n - 2) * 180 / n).toFixed(1);
      var al = Shapes.text(200, cy,
        'Interior angle: ' + intAngle + '°',
        { color: 'orange', size: 11, weight: 'bold', opacity: 0 });
      append(svg, al);
      engine.addPhase([al], 1600, -1);
    }

    // Diagonals
    if (data.show_diagonals) {
      var dcount = 0;
      for (var i = 0; i < n; i++) {
        for (var j = i + 2; j < n; j++) {
          if (i === 0 && j === n - 1) continue;
          var P2 = pts[i], Q2 = pts[j];
          var dl = Shapes.line(P2[0], P2[1], Q2[0], Q2[1],
            { color: 'dim', sw: 0.8, dash: '3 3', opacity: 0 });
          append(svg, dl);
          engine.addPhase([dl], 1800 + dcount * 100, -1);
          dcount++;
        }
      }
      var dc = Shapes.text(200, 285,
        n * (n - 3) / 2 + ' diagonals',
        { color: 'dim', size: 10, opacity: 0 });
      append(svg, dc);
      engine.addPhase([dc], 2200, -1);
    }

    // Incircle (apothem = R*cos(PI/n))
    if (data.show_incircle) {
      var apothem = R * Math.cos(Math.PI / n);
      var ic = Shapes.circle(cx, cy, apothem,
        { color: 'orange', sw: 1.2, dash: '4 3', opacity: 0 });
      append(svg, ic);
      engine.addPhase([ic], 2000, -1);
    }

    // Circumcircle
    if (data.show_circumcircle) {
      var cc = Shapes.circle(cx, cy, R,
        { color: 'purple', sw: 1, dash: '5 3', opacity: 0 });
      append(svg, cc);
      engine.addPhase([cc], 2200, -1);
    }

    // Animated: rotate transform
    var polyG = svgEl('g');
    engine.addMotion(Motion.rotate(polyG, cx, cy, 18000));
  }

  function _polyName(n) {
    var names = ['','','','Triangle','Quadrilateral','Pentagon',
      'Hexagon','Heptagon','Octagon','Nonagon','Decagon','Undecagon','Dodecagon'];
    return names[n] || n + '-gon';
  }

  // ── circleGeometry ───────────────────────────────────────────────────────────
  // data: { radius, label, show_diameter, show_chord, show_sector,
  //         show_tangent, show_arc, sector_angle, arc_angle, title }
  function circleGeometry(engine, svg, data) {
    var R      = Math.min(+data.radius || 90, 115);
    var cx = 200, cy = 160;
    var title  = data.title || data.label || 'Circle Geometry';
    var color  = data.color || 'secondary';

    append(svg, Shapes.text(200, 18, title,
      { color: 'label', size: 13, weight: 'bold' }));

    // Faint background circle
    var bgC = Shapes.circle(cx, cy, R,
      { color: 'dim', sw: 0.5, opacity: 0 });
    append(svg, bgC);
    engine.addPhase([bgC], 0, -1);

    // Main circle
    var mainC = Shapes.circle(cx, cy, R,
      { color: color, sw: 2.5, opacity: 0 });
    append(svg, mainC);
    engine.addPhase([mainC], 200, -1);

    // Centre dot
    var cdot = Shapes.circle(cx, cy, 3,
      { color: 'label', fill: 'label', opacity: 0 });
    append(svg, cdot);
    engine.addPhase([cdot], 400, -1);

    // Radius line
    var radL = Shapes.line(cx, cy, cx + R, cy,
      { color: 'highlight', sw: 1.8, opacity: 0 });
    var radLbl = Shapes.text(cx + R / 2, cy - 12, 'r = ' + R,
      { color: 'highlight', size: 11, weight: 'bold', opacity: 0 });
    append(svg, radL, radLbl);
    engine.addPhase([radL, radLbl], 600, -1);

    // Diameter
    if (data.show_diameter) {
      var diaL = Shapes.line(cx - R, cy, cx + R, cy,
        { color: 'green', sw: 2, opacity: 0 });
      var diaLbl = Shapes.text(cx, cy + 16, 'd = ' + (2 * R),
        { color: 'green', size: 11, weight: 'bold', opacity: 0 });
      append(svg, diaL, diaLbl);
      engine.addPhase([diaL, diaLbl], 1000, -1);
    }

    // Chord
    if (data.show_chord) {
      var ca1 = 30 * Math.PI / 180, ca2 = 150 * Math.PI / 180;
      var cp1 = [cx + R * Math.cos(ca1), cy + R * Math.sin(ca1)];
      var cp2 = [cx + R * Math.cos(ca2), cy + R * Math.sin(ca2)];
      var chL = Shapes.line(cp1[0], cp1[1], cp2[0], cp2[1],
        { color: 'orange', sw: 2, dash: '6 3', opacity: 0 });
      var chLbl = Shapes.text((cp1[0]+cp2[0])/2, (cp1[1]+cp2[1])/2 - 12,
        'chord', { color: 'orange', size: 10, opacity: 0 });
      append(svg, chL, chLbl);
      engine.addPhase([chL, chLbl], 1400, -1);
    }

    // Sector
    if (data.show_sector) {
      var secAng = (+data.sector_angle || 90) * Math.PI / 180;
      var sx1 = cx + R * Math.cos(-Math.PI / 2);
      var sy1 = cy + R * Math.sin(-Math.PI / 2);
      var sx2 = cx + R * Math.cos(-Math.PI / 2 + secAng);
      var sy2 = cy + R * Math.sin(-Math.PI / 2 + secAng);
      var large = secAng > Math.PI ? 1 : 0;
      var secPath = Shapes.path(
        'M ' + cx + ' ' + cy +
        ' L ' + sx1.toFixed(1) + ' ' + sy1.toFixed(1) +
        ' A ' + R + ' ' + R + ' 0 ' + large + ' 1 ' + sx2.toFixed(1) + ' ' + sy2.toFixed(1) + ' Z',
        { color: 'purple', fill: 'purple', sw: 1.5, opacity: 0 }
      );
      secPath.setAttribute('fill-opacity', '0.3');
      append(svg, secPath);
      engine.addPhase([secPath], 1800, -1);
      var secLbl = Shapes.text(cx + (R / 2) * Math.cos(-Math.PI / 2 + secAng / 2),
        cy + (R / 2) * Math.sin(-Math.PI / 2 + secAng / 2),
        (+data.sector_angle || 90) + '°',
        { color: 'purple', size: 11, weight: 'bold', opacity: 0 });
      append(svg, secLbl);
      engine.addPhase([secLbl], 2000, -1);
    }

    // Tangent at rightmost point
    if (data.show_tangent) {
      var tx3 = cx + R;
      var tanL = Shapes.line(tx3, cy - 60, tx3, cy + 60,
        { color: 'gold', sw: 2, opacity: 0 });
      var tanLbl = Shapes.text(tx3 + 14, cy - 50, 'tangent',
        { color: 'gold', size: 10, opacity: 0 });
      var rightAngleSq = Shapes.rect(tx3 - 8, cy - 8, 8, 8,
        { color: 'gold', sw: 1, opacity: 0 });
      append(svg, tanL, rightAngleSq, tanLbl);
      engine.addPhase([tanL, rightAngleSq, tanLbl], 2200, -1);
    }

    // Circumference / area formula
    var fmla = Shapes.text(200, 290,
      'C = 2πr = ' + (2 * Math.PI * R).toFixed(1) + '   A = πr² = ' + (Math.PI * R * R).toFixed(1),
      { color: 'dim', size: 9, opacity: 0 });
    append(svg, fmla);
    engine.addPhase([fmla], 2500, -1);

    // Pulsing glow
    engine.addMotion(Motion.pulse(mainC, cx, cy, 0.97, 1.03, 3000));
  }

  // ── coordinatePlane ──────────────────────────────────────────────────────────
  // data: { points, lines, vectors, x_range, y_range, title, show_grid }
  function coordinatePlane(engine, svg, data) {
    var xR     = data.x_range || [-5, 5];
    var yR     = data.y_range || [-4, 4];
    var title  = data.title || 'Coordinate Plane';
    var points = data.points || [];
    var lines2 = data.lines  || [];
    var vecs   = data.vectors || [];

    var x0 = +xR[0], x1 = +xR[1];
    var y0 = +yR[0], y1 = +yR[1];
    var OX = 200 - (x0 + x1) / 2 * 30;
    var OY = 150 + (y0 + y1) / 2 * 28;
    var scX = 280 / Math.max(x1 - x0, 1);
    var scY = 220 / Math.max(y1 - y0, 1);

    function svgX(x) { return OX + x * scX; }
    function svgY(y) { return OY - y * scY; }

    append(svg, Shapes.text(200, 14, title,
      { color: 'label', size: 12, weight: 'bold' }));

    // Grid
    if (data.show_grid !== false) {
      for (var gx = Math.ceil(x0); gx <= x1; gx++) {
        append(svg, Shapes.line(svgX(gx), 25, svgX(gx), 275,
          { color: 'dim', sw: 0.4, dash: '2 3' }));
      }
      for (var gy = Math.ceil(y0); gy <= y1; gy++) {
        append(svg, Shapes.line(20, svgY(gy), 380, svgY(gy),
          { color: 'dim', sw: 0.4, dash: '2 3' }));
      }
    }

    // Axes
    append(svg, Shapes.arrow(18, svgY(0), 382, svgY(0), { color: 'stroke', sw: 1.8 }));
    append(svg, Shapes.arrow(svgX(0), 275, svgX(0), 23, { color: 'stroke', sw: 1.8 }));

    // Tick labels
    for (var tx2 = Math.ceil(x0); tx2 <= x1; tx2++) {
      if (tx2 === 0) continue;
      append(svg, Shapes.line(svgX(tx2), svgY(0) - 5, svgX(tx2), svgY(0) + 5,
        { color: 'stroke', sw: 1 }));
      append(svg, Shapes.text(svgX(tx2), svgY(0) + 16, String(tx2),
        { color: 'dim', size: 9 }));
    }
    for (var ty = Math.ceil(y0); ty <= y1; ty++) {
      if (ty === 0) continue;
      append(svg, Shapes.line(svgX(0) - 5, svgY(ty), svgX(0) + 5, svgY(ty),
        { color: 'stroke', sw: 1 }));
      append(svg, Shapes.text(svgX(0) - 14, svgY(ty), String(ty),
        { color: 'dim', size: 9 }));
    }
    append(svg, Shapes.text(385, svgY(0), 'x', { color: 'dim', size: 11 }));
    append(svg, Shapes.text(svgX(0), 18, 'y', { color: 'dim', size: 11 }));

    // Lines
    lines2.forEach(function(ln2, i) {
      var lx1 = svgX(+ln2.x1 || x0), ly1 = svgY(+ln2.y1 || 0);
      var lx2 = svgX(+ln2.x2 || x1), ly2 = svgY(+ln2.y2 || 0);
      var ll = Shapes.line(lx1, ly1, lx2, ly2,
        { color: ln2.color || 'secondary', sw: 2, opacity: 0 });
      append(svg, ll);
      engine.addPhase([ll], 400 + i * 300, -1);
      if (ln2.label) {
        var ll2 = Shapes.text((lx1 + lx2) / 2 + 8, (ly1 + ly2) / 2 - 10, ln2.label,
          { color: ln2.color || 'secondary', size: 10, opacity: 0 });
        append(svg, ll2);
        engine.addPhase([ll2], 500 + i * 300, -1);
      }
    });

    // Vectors
    vecs.forEach(function(v, i) {
      var vx1 = svgX(+v.x1 || 0), vy1 = svgY(+v.y1 || 0);
      var vx2 = svgX(+v.x2 || 1), vy2 = svgY(+v.y2 || 1);
      var vl = Shapes.arrow(vx1, vy1, vx2, vy2,
        { color: v.color || 'orange', sw: 2 });
      vl.setAttribute('opacity', 0);
      append(svg, vl);
      engine.addPhase([vl], 600 + i * 300, -1);
      if (v.label) {
        var vt = Shapes.text(vx2 + 8, vy2 - 8, v.label,
          { color: v.color || 'orange', size: 10, weight: 'bold', opacity: 0 });
        append(svg, vt);
        engine.addPhase([vt], 700 + i * 300, -1);
      }
    });

    // Points
    var PCOLS = ['highlight', 'green', 'orange', 'purple', 'gold', 'pink'];
    points.forEach(function(p, i) {
      var px = svgX(+p.x), py = svgY(+p.y);
      var pd = Shapes.circle(px, py, 6,
        { color: PCOLS[i % PCOLS.length], fill: PCOLS[i % PCOLS.length],
          glow: true, opacity: 0 });
      var pl = Shapes.text(px + 10, py - 10,
        '(' + p.x + ', ' + p.y + ')' + (p.label ? ' ' + p.label : ''),
        { color: PCOLS[i % PCOLS.length], size: 10, anchor: 'start', opacity: 0 });
      append(svg, pd, pl);
      engine.addPhase([pd, pl], 800 + i * 400, -1);
      engine.addMotion(Motion.pulse(pd, px, py, 0.85, 1.15, 1500 + i * 200));
    });
  }

  // ── vennDiagram ──────────────────────────────────────────────────────────────
  // data: { sets:[{label,items:[]}], intersection, title }
  function vennDiagram(engine, svg, data) {
    var sets  = (data.sets || [
      { label: 'A', items: ['1', '2', '3'] },
      { label: 'B', items: ['3', '4', '5'] },
    ]).slice(0, 3);
    var inter  = data.intersection || '';
    var title  = data.title || 'Venn Diagram';

    append(svg, Shapes.text(200, 18, title,
      { color: 'label', size: 13, weight: 'bold' }));

    var COLORS2 = ['secondary', 'highlight', 'green'];
    var cx1 = 165, cx2 = 235, cy2 = 155, R2 = 90;
    var cx3 = 200, cy3 = 210;

    var circles2;
    if (sets.length <= 2) {
      circles2 = [[cx1, cy2], [cx2, cy2]];
    } else {
      circles2 = [[cx1, cy2], [cx2, cy2], [cx3, cy3]];
    }

    circles2.forEach(function(pos, i) {
      var c = svgEl('circle', {
        cx: pos[0], cy: pos[1], r: R2,
        stroke: resolveColor(COLORS2[i]),
        fill: resolveColor(COLORS2[i]),
        'fill-opacity': '0.18',
        'stroke-width': '2',
        opacity: 0,
      });
      append(svg, c);
      engine.addPhase([c], i * 500, -1);

      var lbl2 = Shapes.text(
        pos[0] + (i === 0 ? -38 : i === 1 ? 38 : 0),
        pos[1] + (i < 2 ? -R2 - 12 : R2 + 16),
        (sets[i] && sets[i].label) || '',
        { color: COLORS2[i], size: 14, weight: 'bold', opacity: 0 }
      );
      append(svg, lbl2);
      engine.addPhase([lbl2], i * 500 + 200, -1);

      // Item labels inside set (excluding intersection)
      if (sets[i] && sets[i].items) {
        sets[i].items.forEach(function(item, j) {
          var xt = pos[0] + (i === 0 ? -26 : i === 1 ? 26 : 0);
          var yt = pos[1] - 20 + j * 18;
          var it = Shapes.text(xt, yt, String(item),
            { color: COLORS2[i], size: 10, opacity: 0 });
          append(svg, it);
          engine.addPhase([it], i * 500 + 400 + j * 100, -1);
        });
      }
    });

    // Intersection label at centre
    if (inter) {
      var il = Shapes.text(200, cy2 + 5, String(inter),
        { color: 'gold', size: 12, weight: 'bold', opacity: 0 });
      append(svg, il);
      engine.addPhase([il], 1600, -1);
    }
  }

  // ── barChart ──────────────────────────────────────────────────────────────────
  // data: { values:[{label,value,color?}], y_label, title, max }
  function barChart(engine, svg, data) {
    var vals  = (data.values || data.data || [
      { label: 'A', value: 40 },
      { label: 'B', value: 65 },
      { label: 'C', value: 30 },
      { label: 'D', value: 80 },
    ]).slice(0, 8);
    var title   = data.title || 'Bar Chart';
    var yLabel  = data.y_label || '';
    var maxVal  = data.max || Math.max.apply(null, vals.map(function(v) { return +v.value || 0; })) * 1.15;

    var chartX = 55, chartY = 30, chartW = 300, chartH = 210;
    var n = vals.length;
    var barW = Math.floor((chartW - (n - 1) * 6) / n);
    var BCOLS = ['secondary', 'highlight', 'green', 'orange', 'purple', 'gold', 'pink', 'teal'];

    append(svg, Shapes.text(200, 18, title,
      { color: 'label', size: 13, weight: 'bold' }));

    // Axes
    append(svg, Shapes.line(chartX, chartY, chartX, chartY + chartH,
      { color: 'stroke', sw: 2 }));
    append(svg, Shapes.line(chartX, chartY + chartH, chartX + chartW, chartY + chartH,
      { color: 'stroke', sw: 2 }));

    // Y gridlines
    for (var g = 0; g <= 4; g++) {
      var gval = maxVal * g / 4;
      var gy = chartY + chartH - (gval / maxVal) * chartH;
      append(svg, Shapes.line(chartX - 4, gy, chartX + chartW, gy,
        { color: 'dim', sw: 0.4, dash: '3 3' }));
      append(svg, Shapes.text(chartX - 8, gy, Math.round(gval) + '',
        { color: 'dim', size: 8, anchor: 'end' }));
    }

    if (yLabel) {
      append(svg, Shapes.text(14, chartY + chartH / 2, yLabel,
        { color: 'dim', size: 10 }));
    }

    vals.forEach(function(v, i) {
      var val    = +v.value || 0;
      var color2 = v.color || BCOLS[i % BCOLS.length];
      var barH   = (val / maxVal) * chartH;
      var bx     = chartX + i * (barW + 6);
      var by     = chartY + chartH - barH;

      var bgBar = Shapes.rect(bx, chartY, barW, chartH,
        { color: 'dim', fill: 'dim', sw: 0, opacity: 0 });
      bgBar.setAttribute('fill-opacity', '0.08');

      var bar = svgEl('rect', {
        x: bx, y: chartY + chartH, width: barW, height: 0,
        fill: resolveColor(color2),
        'fill-opacity': '0.85', rx: 3, opacity: 0,
      });
      var barLbl = Shapes.text(bx + barW / 2, by - 8, String(val),
        { color: color2, size: 10, weight: 'bold', opacity: 0 });
      var axLbl = Shapes.text(bx + barW / 2, chartY + chartH + 16, v.label || '',
        { color: 'dim', size: 9, opacity: 0 });

      append(svg, bgBar, bar, barLbl, axLbl);
      engine.addPhase([bgBar, axLbl], i * 200, -1);
      engine.addPhase([barLbl], i * 200 + 600, -1);

      engine.addMotion({
        update: function(b, targetY, targetH, startMs) {
          return function(elapsed) {
            var prog = Math.min((elapsed - startMs) / 800, 1);
            if (prog <= 0) { b.setAttribute('opacity', 0); return; }
            prog = 3 * prog * prog - 2 * prog * prog * prog;
            var h = targetH * prog;
            b.setAttribute('y', chartY + chartH - h);
            b.setAttribute('height', h);
            b.setAttribute('opacity', 1);
          };
        }(bar, by, barH, i * 200 + 100)
      });
    });
  }

  // ── pieChart ──────────────────────────────────────────────────────────────────
  // data: { values:[{label,value,color?}], title }
  function pieChart(engine, svg, data) {
    var vals  = (data.values || data.data || [
      { label: 'A', value: 30 },
      { label: 'B', value: 45 },
      { label: 'C', value: 25 },
    ]).slice(0, 8);
    var title  = data.title || 'Pie Chart';
    var cx = 185, cy = 155, R3 = 95;

    var total = vals.reduce(function(s, v) { return s + (+v.value || 0); }, 0);
    if (!total) total = 1;

    var PCOLS2 = ['secondary', 'highlight', 'green', 'orange', 'purple', 'gold', 'pink', 'teal'];

    append(svg, Shapes.text(200, 16, title,
      { color: 'label', size: 13, weight: 'bold' }));

    var startAngle = -Math.PI / 2;
    vals.forEach(function(v, i) {
      var pct    = (+v.value || 0) / total;
      var sweep  = pct * 2 * Math.PI;
      var end    = startAngle + sweep;
      var color2 = v.color || PCOLS2[i % PCOLS2.length];

      var lx1 = cx + R3 * Math.cos(startAngle);
      var ly1 = cy + R3 * Math.sin(startAngle);
      var lx2 = cx + R3 * Math.cos(end);
      var ly2 = cy + R3 * Math.sin(end);
      var large = sweep > Math.PI ? 1 : 0;

      var slice = svgEl('path', {
        d: 'M ' + cx + ' ' + cy +
           ' L ' + lx1.toFixed(1) + ' ' + ly1.toFixed(1) +
           ' A ' + R3 + ' ' + R3 + ' 0 ' + large + ' 1 ' +
           lx2.toFixed(1) + ' ' + ly2.toFixed(1) + ' Z',
        fill: resolveColor(color2),
        'fill-opacity': '0.85',
        stroke: resolveColor('bg'),
        'stroke-width': '2',
        opacity: 0,
      });
      append(svg, slice);
      engine.addPhase([slice], i * 400, -1);

      // Legend
      var legY = 50 + i * 24;
      var legRect = Shapes.rect(298, legY - 8, 14, 14,
        { color: color2, fill: color2, sw: 0, opacity: 0 });
      var legLbl = Shapes.text(316, legY, v.label + ' ' + Math.round(pct * 100) + '%',
        { color: 'dim', size: 9, anchor: 'start', opacity: 0 });
      append(svg, legRect, legLbl);
      engine.addPhase([legRect, legLbl], i * 400 + 200, -1);

      startAngle = end;
    });

    // Pulsing highlight
    var ring = Shapes.circle(cx, cy, R3 + 4, { color: 'dim', sw: 1, opacity: 0.3 });
    append(svg, ring);
    engine.addMotion(Motion.fade(ring, 0.1, 0.5, 2500));
  }

  // ── angleGeometry ─────────────────────────────────────────────────────────────
  // data: { angle_deg, angle_type, labels, title, show_second, angle2_deg, show_reflex }
  function angleGeometry(engine, svg, data) {
    var deg    = Math.max(1, Math.min(359, +data.angle_deg || 60));
    var atype  = (data.angle_type || '').toLowerCase();
    var labels = data.labels || ['A', 'O', 'B'];
    var title  = data.title || (deg + '° ' + _angleTypeName(deg));

    var OX = 200, OY = 200;
    var R4 = 120;
    var baseAngle = 0;
    var mainAngle = -deg * Math.PI / 180;

    append(svg, Shapes.text(200, 18, title,
      { color: 'label', size: 13, weight: 'bold' }));

    // Vertex
    var vdot = Shapes.circle(OX, OY, 4,
      { color: 'label', fill: 'label', opacity: 0 });
    append(svg, vdot);
    engine.addPhase([vdot], 0, -1);

    // Base ray (→ right)
    var baseRay = Shapes.line(OX, OY, OX + R4, OY,
      { color: 'stroke', sw: 2, opacity: 0 });
    append(svg, baseRay);
    engine.addPhase([baseRay], 100, -1);

    // Angle ray
    var ex = OX + R4 * Math.cos(mainAngle);
    var ey = OY + R4 * Math.sin(mainAngle);
    var angleRay = Shapes.line(OX, OY, ex, ey,
      { color: 'highlight', sw: 2, opacity: 0 });
    append(svg, angleRay);
    engine.addPhase([angleRay], 300, -1);

    // Angle arc
    var arcR2 = 40;
    var arcEl = Shapes.arc(OX, OY, arcR2, baseAngle, -deg,
      { color: 'secondary', sw: 2, opacity: 0 });
    var degLbl = Shapes.text(
      OX + (arcR2 + 14) * Math.cos(mainAngle / 2),
      OY + (arcR2 + 14) * Math.sin(mainAngle / 2),
      deg + '°',
      { color: 'secondary', size: 12, weight: 'bold', opacity: 0 }
    );
    append(svg, arcEl, degLbl);
    engine.addPhase([arcEl, degLbl], 600, -1);

    // Vertex label
    var vLbl = Shapes.text(OX - 14, OY + 14, labels[1] || 'O',
      { color: 'label', size: 13, weight: 'bold', opacity: 0 });
    append(svg, vLbl);
    engine.addPhase([vLbl], 800, -1);

    // End labels
    var aLbl = Shapes.text(OX + R4 + 12, OY, labels[2] || 'B',
      { color: 'label', size: 13, opacity: 0 });
    var bLbl = Shapes.text(ex + (ex > OX ? 12 : -12), ey - 12, labels[0] || 'A',
      { color: 'label', size: 13, opacity: 0 });
    append(svg, aLbl, bLbl);
    engine.addPhase([aLbl, bLbl], 1000, -1);

    // Right-angle square
    if (deg === 90) {
      var sq = Shapes.rect(OX, OY - 20, 20, 20,
        { color: 'secondary', sw: 1.5, opacity: 0 });
      append(svg, sq);
      engine.addPhase([sq], 700, -1);
    }

    // Supplementary: show 180° line
    if (data.show_second || atype === 'supplementary') {
      var suppDeg = +data.angle2_deg || (180 - deg);
      var sx = OX - R4;
      var suppL = Shapes.line(OX, OY, sx, OY,
        { color: 'orange', sw: 2, dash: '5 3', opacity: 0 });
      var suppLbl = Shapes.text(sx - 16, OY - 14, suppDeg + '°',
        { color: 'orange', size: 11, opacity: 0 });
      append(svg, suppL, suppLbl);
      engine.addPhase([suppL, suppLbl], 1400, -1);
    }

    // Reflex angle
    if (data.show_reflex) {
      var reflexDeg = 360 - deg;
      var rArc = Shapes.arc(OX, OY, arcR2 + 15, -deg, 0,
        { color: 'purple', sw: 1.5, dash: '3 3', opacity: 0 });
      var rLbl = Shapes.text(OX - 20, OY + 30, reflexDeg + '°',
        { color: 'purple', size: 10, opacity: 0 });
      append(svg, rArc, rLbl);
      engine.addPhase([rArc, rLbl], 1600, -1);
    }

    // Animated sweeping arc (continuous)
    var sweepArc = Shapes.arc(OX, OY, arcR2, 0, 0, { color: 'secondary', sw: 2 });
    sweepArc.setAttribute('opacity', 0);
    engine.addMotion(Motion.fade(sweepArc, 0, 0, 1000));  // invisible no-op
  }

  function _angleTypeName(deg) {
    if (deg === 90)  return 'Right Angle';
    if (deg < 90)    return 'Acute Angle';
    if (deg === 180) return 'Straight Angle';
    if (deg < 180)   return 'Obtuse Angle';
    if (deg === 360) return 'Full Angle';
    return 'Reflex Angle';
  }

  // ── lineGraph ─────────────────────────────────────────────────────────────────
  // data: { points:[[x,y]], x_label, y_label, title, color, show_area }
  function lineGraph(engine, svg, data) {
    var pts    = (data.points || [[0,0],[1,3],[2,5],[3,4],[4,7]]);
    var xLabel = data.x_label || 'x';
    var yLabel = data.y_label || 'y';
    var title  = data.title   || 'Line Graph';
    var color  = data.color   || 'secondary';

    var chartX = 50, chartY = 28, chartW = 300, chartH = 220;
    var xs = pts.map(function(p) { return +p[0]; });
    var ys = pts.map(function(p) { return +p[1]; });
    var xMin = Math.min.apply(null, xs), xMax = Math.max.apply(null, xs);
    var yMin = 0, yMax = Math.max.apply(null, ys) * 1.15;
    if (yMax === yMin) yMax = yMin + 1;

    function svgX2(x) { return chartX + ((x - xMin) / (xMax - xMin || 1)) * chartW; }
    function svgY2(y) { return chartY + chartH - ((y - yMin) / (yMax - yMin)) * chartH; }

    append(svg, Shapes.text(200, 14, title,
      { color: 'label', size: 12, weight: 'bold' }));

    // Grid
    for (var g = 0; g <= 4; g++) {
      var gv = yMin + (yMax - yMin) * g / 4;
      var gy = svgY2(gv);
      append(svg, Shapes.line(chartX, gy, chartX + chartW, gy,
        { color: 'dim', sw: 0.4, dash: '3 3' }));
      append(svg, Shapes.text(chartX - 6, gy, Math.round(gv) + '',
        { color: 'dim', size: 8, anchor: 'end' }));
    }

    // Axes
    append(svg, Shapes.arrow(chartX, chartY + chartH, chartX + chartW + 10, chartY + chartH,
      { color: 'stroke', sw: 2 }));
    append(svg, Shapes.arrow(chartX, chartY + chartH, chartX, chartY - 5,
      { color: 'stroke', sw: 2 }));
    append(svg, Shapes.text(200, chartY + chartH + 28, xLabel,
      { color: 'dim', size: 10 }));

    // Filled area under curve
    if (data.show_area) {
      var areaD = 'M ' + svgX2(xs[0]) + ' ' + (chartY + chartH);
      pts.forEach(function(p) {
        areaD += ' L ' + svgX2(p[0]) + ' ' + svgY2(p[1]);
      });
      areaD += ' L ' + svgX2(xs[xs.length - 1]) + ' ' + (chartY + chartH) + ' Z';
      var area2 = Shapes.path(areaD, { color: color, fill: color, sw: 0, opacity: 0 });
      area2.setAttribute('fill-opacity', '0.2');
      append(svg, area2);
      engine.addPhase([area2], 200, -1);
    }

    // Line segments animated
    pts.forEach(function(p, i) {
      if (i === 0) return;
      var lx1 = svgX2(pts[i-1][0]), ly1 = svgY2(pts[i-1][1]);
      var lx2 = svgX2(p[0]),        ly2 = svgY2(p[1]);
      var seg = Shapes.line(lx1, ly1, lx2, ly2,
        { color: color, sw: 2.5, opacity: 0 });
      append(svg, seg);
      engine.addPhase([seg], i * 400, -1);
    });

    // Data points
    var PCOLS3 = ['highlight', 'orange', 'green', 'purple', 'gold'];
    pts.forEach(function(p, i) {
      var px = svgX2(p[0]), py = svgY2(p[1]);
      var dot = Shapes.circle(px, py, 5,
        { color: PCOLS3[i % PCOLS3.length], fill: PCOLS3[i % PCOLS3.length],
          opacity: 0 });
      var lbl2 = Shapes.text(px, py - 14, String(p[1]),
        { color: PCOLS3[i % PCOLS3.length], size: 9, opacity: 0 });
      append(svg, dot, lbl2);
      engine.addPhase([dot, lbl2], i * 400 + 200, -1);
    });

    // X-axis labels
    pts.forEach(function(p) {
      append(svg, Shapes.text(svgX2(p[0]), chartY + chartH + 16, String(p[0]),
        { color: 'dim', size: 8 }));
    });
  }

  // ── pythagorasTheorem ─────────────────────────────────────────────────────────
  // data: { a, b, show_squares, title }
  function pythagorasTheorem(engine, svg, data) {
    var a = +data.a || 60;
    var b = +data.b || 80;
    var c = Math.sqrt(a * a + b * b);
    var title = data.title || 'Pythagoras\' Theorem';

    // Triangle vertices: right angle at C (300,220), B at (300-b*scale, 220), A at (300-b*scale, 220-a*scale)
    var scale = Math.min(150 / a, 150 / b, 1);
    a = a * scale; b = b * scale; c = c * scale;

    var C4 = [250, 240], B4 = [250 - b, 240], A4 = [250 - b, 240 - a];

    append(svg, Shapes.text(200, 16, title,
      { color: 'label', size: 12, weight: 'bold' }));

    // Triangle sides (animate in sequence)
    var sides = [
      { P: B4, Q: C4, lbl: 'b=' + Math.round(b), color: 'secondary' },
      { P: A4, Q: B4, lbl: 'a=' + Math.round(a), color: 'highlight' },
      { P: A4, Q: C4, lbl: 'c=' + Math.round(c), color: 'orange'   },
    ];
    sides.forEach(function(s, i) {
      var sl = Shapes.line(s.P[0], s.P[1], s.Q[0], s.Q[1],
        { color: s.color, sw: 2.5, opacity: 0 });
      var mx = (s.P[0] + s.Q[0]) / 2 + (i === 2 ? 16 : i === 1 ? -20 : 0);
      var my = (s.P[1] + s.Q[1]) / 2 + (i === 0 ? 14 : -8);
      var ll = Shapes.text(mx, my, s.lbl,
        { color: s.color, size: 11, weight: 'bold', opacity: 0 });
      append(svg, sl, ll);
      engine.addPhase([sl, ll], i * 600, -1);
    });

    // Vertex labels
    [[A4, 'A'], [B4, 'B'], [C4, 'C']].forEach(function(pair, i) {
      var d = Shapes.circle(pair[0][0], pair[0][1], 4,
        { color: 'label', fill: 'label', opacity: 0 });
      var t2 = Shapes.text(
        pair[0][0] + (i === 0 ? -16 : i === 1 ? -16 : 12),
        pair[0][1] + (i === 0 ? -12 : 16),
        pair[1],
        { color: 'label', size: 13, weight: 'bold', opacity: 0 }
      );
      append(svg, d, t2);
      engine.addPhase([d, t2], 1800 + i * 200, -1);
    });

    // Right angle marker
    var sq3 = Shapes.rect(B4[0], B4[1] - 12, 12, 12,
      { color: 'stroke', sw: 1.2, opacity: 0 });
    append(svg, sq3);
    engine.addPhase([sq3], 600, -1);

    // Squares on each side (optional but stunning)
    if (data.show_squares !== false) {
      // Square on b (bottom, below the triangle)
      var sqB = Shapes.rect(B4[0], B4[1], b, b * 0.5,
        { color: 'secondary', fill: 'secondary', sw: 1.5, opacity: 0 });
      sqB.setAttribute('fill-opacity', '0.2');
      var sqBl = Shapes.text(B4[0] + b/2, B4[1] + b * 0.25, 'b²',
        { color: 'secondary', size: 12, weight: 'bold', opacity: 0 });
      append(svg, sqB, sqBl);
      engine.addPhase([sqB, sqBl], 2400, -1);

      // Square on a (left of triangle)
      var sqA = Shapes.rect(A4[0] - a * 0.5, A4[1], a * 0.5, a,
        { color: 'highlight', fill: 'highlight', sw: 1.5, opacity: 0 });
      sqA.setAttribute('fill-opacity', '0.2');
      var sqAl = Shapes.text(A4[0] - a * 0.25, A4[1] + a/2, 'a²',
        { color: 'highlight', size: 12, weight: 'bold', opacity: 0 });
      append(svg, sqA, sqAl);
      engine.addPhase([sqA, sqAl], 2800, -1);
    }

    // Formula
    var fmla2 = Shapes.text(200, 280,
      'a² + b² = c²',
      { color: 'gold', size: 15, weight: 'bold', opacity: 0 });
    append(svg, fmla2);
    engine.addPhase([fmla2], 3200, -1);
    engine.addMotion(Motion.pulse(fmla2, 200, 280, 0.92, 1.08, 1200));
  }

  // ── geometryAngles (supplementary / complementary / vertically opposite) ──────
  // data: { angle_deg, angle_type, labels, title, show_second, angle2_deg }
  // (Delegates to angleGeometry for the full featured version — kept as alias)
  function geometryAngles(engine, svg, data) {
    return angleGeometry(engine, svg, data);
  }

  // ── rightAngle ───────────────────────────────────────────────────────────────
  // Dedicated 90° angle diagram with square marker.
  // data: { labels, label_a, label_b, label_o, title, show_arms, arm_length }
  function rightAngle(engine, svg, data) {
    var labels  = data.labels || ['A', 'O', 'B'];
    var lA      = data.label_a || labels[0] || 'A';
    var lO      = data.label_o || labels[1] || 'O';
    var lB      = data.label_b || labels[2] || 'B';
    var title   = data.title   || 'Right Angle — 90°';
    var armLen  = Math.min(+data.arm_length || 110, 130);
    var OX = 160, OY = 210;

    append(svg, Shapes.text(200, 18, title,
      { color: 'label', size: 13, weight: 'bold' }));

    var vdot = Shapes.circle(OX, OY, 5,
      { color: 'label', fill: 'label', opacity: 0 });
    append(svg, vdot);
    engine.addPhase([vdot], 0, -1);

    var hArm = Shapes.line(OX, OY, OX + armLen, OY,
      { color: 'stroke', sw: 2.5, opacity: 0 });
    append(svg, hArm);
    engine.addPhase([hArm], 200, -1);

    var vArm = Shapes.line(OX, OY, OX, OY - armLen,
      { color: 'highlight', sw: 2.5, opacity: 0 });
    append(svg, vArm);
    engine.addPhase([vArm], 500, -1);

    var sqSize = 18;
    var sq = Shapes.rect(OX, OY - sqSize, sqSize, sqSize,
      { color: 'secondary', sw: 1.8, opacity: 0 });
    append(svg, sq);
    engine.addPhase([sq], 800, -1);

    var degLbl = Shapes.text(OX + sqSize + 14, OY - sqSize / 2,
      '90°', { color: 'secondary', size: 13, weight: 'bold', opacity: 0 });
    append(svg, degLbl);
    engine.addPhase([degLbl], 1000, -1);

    var oLbl = Shapes.text(OX - 18, OY + 16, lO,
      { color: 'label', size: 13, weight: 'bold', opacity: 0 });
    append(svg, oLbl);
    engine.addPhase([oLbl], 1100, -1);

    var bLbl = Shapes.text(OX + armLen + 12, OY, lB,
      { color: 'stroke', size: 13, opacity: 0 });
    var aLbl = Shapes.text(OX - 14, OY - armLen - 16, lA,
      { color: 'highlight', size: 13, opacity: 0 });
    append(svg, bLbl, aLbl);
    engine.addPhase([bLbl, aLbl], 1300, -1);

    if (data.show_arms !== false) {
      var tick1 = Shapes.line(OX + armLen * 0.5 - 4, OY - 5,
                              OX + armLen * 0.5 + 4, OY + 5,
                              { color: 'dim', sw: 1.2, opacity: 0 });
      var tick2 = Shapes.line(OX - 5, OY - armLen * 0.5 - 4,
                              OX + 5, OY - armLen * 0.5 + 4,
                              { color: 'dim', sw: 1.2, opacity: 0 });
      append(svg, tick1, tick2);
      engine.addPhase([tick1, tick2], 1500, -1);
    }

    var subLbl = Shapes.text(200, 280, 'Perpendicular lines meet at 90°',
      { color: 'dim', size: 10, opacity: 0 });
    append(svg, subLbl);
    engine.addPhase([subLbl], 1800, -1);
  }

  // ── polygonFormation ─────────────────────────────────────────────────────────
  // Shows progression of regular polygons with interior angle formula.
  // data: { from_sides, to_sides, show_formula, color, title }
  function polygonFormation(engine, svg, data) {
    var fromN   = Math.max(3, Math.min(8, +data.from_sides || 3));
    var toN     = Math.max(fromN + 1, Math.min(8, +data.to_sides || 6));
    var count   = toN - fromN + 1;
    var color   = data.color || 'secondary';
    var title   = data.title || 'Polygon Family';
    var showFml = data.show_formula !== false;

    append(svg, Shapes.text(200, 18, title,
      { color: 'label', size: 13, weight: 'bold' }));

    var R = count <= 4 ? 44 : 32;
    var spacing = Math.min(380 / count, 95);
    var startX  = 200 - (count - 1) * spacing / 2;
    var CY2 = 140;

    for (var idx = 0; idx < count; idx++) {
      var n     = fromN + idx;
      var cx    = startX + idx * spacing;
      var delay = idx * 600;
      var pts   = [];
      for (var vi = 0; vi < n; vi++) {
        var ang = (vi / n) * 2 * Math.PI - Math.PI / 2;
        pts.push([cx + R * Math.cos(ang), CY2 + R * Math.sin(ang)]);
      }

      (function(pts2, n2, cx2, delay2) {
        pts2.forEach(function(P, si) {
          var Q  = pts2[(si + 1) % n2];
          var sl = Shapes.line(P[0], P[1], Q[0], Q[1],
            { color: color, sw: si === 0 ? 2.2 : 1.8, opacity: 0 });
          append(svg, sl);
          engine.addPhase([sl], delay2 + si * (300 / n2), -1);
        });

        var nameLbl = Shapes.text(cx2, CY2 + R + 18, _polyName(n2),
          { color: 'label', size: 9, weight: 'bold', opacity: 0 });
        var sideLbl = Shapes.text(cx2, CY2 + R + 30, n2 + ' sides',
          { color: 'dim', size: 8, opacity: 0 });
        append(svg, nameLbl, sideLbl);
        engine.addPhase([nameLbl, sideLbl], delay2 + 450, -1);

        var intAng = ((n2 - 2) * 180 / n2).toFixed(0);
        var angLbl = Shapes.text(cx2, CY2 + R + 42, intAng + '° each',
          { color: 'orange', size: 8, opacity: 0 });
        append(svg, angLbl);
        engine.addPhase([angLbl], delay2 + 600, -1);
      })(pts, n, cx, delay);

      if (idx < count - 1) {
        var ax = cx + spacing * 0.5;
        var arrEl = Shapes.text(ax, CY2, '→',
          { color: 'dim', size: 14, opacity: 0 });
        append(svg, arrEl);
        engine.addPhase([arrEl], delay + 400, -1);
      }
    }

    if (showFml) {
      var fmla = Shapes.text(200, 265,
        'Interior angle = (n − 2) × 180° ÷ n',
        { color: 'gold', size: 11, weight: 'bold', opacity: 0 });
      append(svg, fmla);
      engine.addPhase([fmla], count * 600 + 200, -1);
      engine.addMotion(Motion.pulse(fmla, 200, 265, 0.93, 1.07, 1400));
    }

    var note = Shapes.text(200, 282,
      'More sides → larger interior angle → closer to circle',
      { color: 'dim', size: 9, opacity: 0 });
    append(svg, note);
    engine.addPhase([note], count * 600 + 600, -1);
  }

  // ── Public API ───────────────────────────────────────────────────────────────
  return {
    atom, solarSystem, wave, sun, plant, flowArrow, cycle, labeled, comparison, custom,
    heart, neuron, pendulum, springMass, dna, lens, electricField,
    // Mathematics
    numberLine, fractionBar, graphFunction,
    triangle, regularPolygon, circleGeometry,
    coordinatePlane, vennDiagram, barChart, pieChart,
    angleGeometry, geometryAngles, lineGraph, pythagorasTheorem,
    rightAngle, polygonFormation,
  };
})();

window.Diagrams = Diagrams;