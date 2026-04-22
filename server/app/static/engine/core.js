/**
 * core.js — DiagramEngine foundation
 *
 * Responsibilities:
 *   - SVG namespace helpers (createEl, setAttrs, appendChild)
 *   - Global animation loop via requestAnimationFrame
 *   - Phase system: steps through teaching phases on a time basis
 *   - Pause / Replay controls
 *   - Filter definitions (glow effect for electrons etc.)
 *
 * All other engine modules (shapes, motion, diagrams) register themselves
 * against the single DiagramEngine instance exported as `window.Engine`.
 *
 * Usage (from html):
 *   <script src="core.js"></script>
 *   <script src="shapes.js"></script>
 *   <script src="motion.js"></script>
 *   <script src="diagrams.js"></script>
 *   <script>
 *     const engine = new DiagramEngine(document.getElementById('svg'), descriptor);
 *     engine.start();
 *   </script>
 */

'use strict';

// ── SVG namespace constant ────────────────────────────────────────────────────
const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * Create an SVG element in the correct namespace.
 * @param {string} tag  e.g. 'circle', 'line', 'text'
 * @param {Object} attrs  key→value attribute map
 * @returns {SVGElement}
 */
function svgEl(tag, attrs) {
  const el = document.createElementNS(SVG_NS, tag);
  if (attrs) setAttrs(el, attrs);
  return el;
}

/**
 * Set multiple attributes on an SVG/HTML element.
 * @param {Element} el
 * @param {Object} attrs
 */
function setAttrs(el, attrs) {
  for (const [k, v] of Object.entries(attrs)) {
    el.setAttribute(k, v);
  }
}

/**
 * Append one or more children to a parent element.
 * @param {Element} parent
 * @param {...Element} children
 */
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

/**
 * Resolve a colour key or raw hex/rgb string.
 * @param {string} key
 * @returns {string}
 */
function resolveColor(key) {
  if (!key) return COLORS.stroke;
  return COLORS[key] || key;
}

// ── SVG filter definitions ────────────────────────────────────────────────────

/**
 * Inject standard <defs> into the SVG: glow filter, arrowhead markers.
 * Call once during engine initialisation.
 * @param {SVGSVGElement} svg
 */
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
  /**
   * @param {SVGSVGElement} svg        Target SVG element
   * @param {Object}        descriptor Scene descriptor (varies by diagram type)
   * @param {Object}        [opts]
   * @param {number}        [opts.phaseMs=2500]   ms per teaching phase
   * @param {boolean}       [opts.loop=true]       loop animation
   */
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

  /**
   * Register an animation motion that receives elapsed ms each frame.
   * @param {{ update: function(number): void }} motion
   */
  addMotion(motion) {
    this._motions.push(motion);
  }

  // ── Phase system ──────────────────────────────────────────────────────────

  /**
   * Register a teaching phase.  Elements in this phase will become visible
   * (opacity 1) when the phase's time window is active; otherwise dim.
   * @param {SVGElement[]} elements   Elements belonging to this phase
   * @param {number}       startMs    Phase activation start (ms from t=0)
   * @param {number}       endMs      Phase activation end (-1 = forever)
   */
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

/**
 * Create pause/replay buttons and wire them to the engine.
 * Appends to document.body unless a container is provided.
 * @param {DiagramEngine} engine
 * @param {HTMLElement}   [container]
 */
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
