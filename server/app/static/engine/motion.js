/**
 * motion.js — Animation motion patterns
 *
 * Each factory returns a motion object with an `update(elapsedMs)` method
 * that the DiagramEngine calls every frame.
 *
 * Available motion types:
 *   Motion.orbit       — circular/elliptical orbit for a DOM element
 *   Motion.oscillate   — sinusoidal back-and-forth movement (x or y)
 *   Motion.linearPath  — move element along a straight path and loop
 *   Motion.pulse       — scale an element up/down rhythmically
 *   Motion.fade        — fade opacity in/out cyclically
 *   Motion.rotate      — spin an SVG element around its own centre
 *   Motion.wave        — animate path d-attribute to simulate a wave
 *
 * Requires core.js (svgEl, setAttrs) to be loaded first.
 *
 * Usage:
 *   const engine = new DiagramEngine(svg, descriptor);
 *   engine.addMotion(Motion.orbit(electronEl, 200, 150, 80, 40, 3000));
 *   engine.start();
 */

'use strict';

const Motion = (() => {

  // ── orbit ────────────────────────────────────────────────────────────────────
  /**
   * Move an SVG element (circle, image…) along an elliptical orbit.
   *
   * @param {SVGElement} el        Element to move (must support cx/cy or x/y)
   * @param {number}     cx        Orbit centre x
   * @param {number}     cy        Orbit centre y
   * @param {number}     rx        Orbit x-radius
   * @param {number}     ry        Orbit y-radius
   * @param {number}     periodMs  Full revolution time (ms)
   * @param {Object}     [opts]
   * @param {number}     [opts.phase=0]      Initial phase offset (0–1)
   * @param {boolean}    [opts.ccw=false]    Counter-clockwise direction
   * @param {boolean}    [opts.useCxy=true]  Use cx/cy attrs (false → x/y)
   * @returns {{ update(elapsed: number): void }}
   */
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
  /**
   * Sinusoidal oscillation along one axis.
   *
   * @param {SVGElement} el
   * @param {'x'|'y'|'cx'|'cy'} attr   Which attribute to animate
   * @param {number} centre             Rest position
   * @param {number} amplitude          Peak displacement
   * @param {number} periodMs
   * @param {{ phase? }} [opts]
   * @returns {{ update(elapsed: number): void }}
   */
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
  /**
   * Move element from (x1,y1) to (x2,y2) and loop.
   *
   * @param {SVGElement} el
   * @param {number} x1 @param {number} y1  start
   * @param {number} x2 @param {number} y2  end
   * @param {number} periodMs  one-way travel time
   * @param {{ pingpong?, useCxy? }} [opts]
   * @returns {{ update(elapsed: number): void }}
   */
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
  /**
   * Smoothly scale an element up and down (highlight effect).
   *
   * @param {SVGElement} el
   * @param {number} cx @param {number} cy  transform origin
   * @param {number} minScale @param {number} maxScale
   * @param {number} periodMs
   * @returns {{ update(elapsed: number): void }}
   */
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
  /**
   * Cyclic opacity fade.
   *
   * @param {SVGElement} el
   * @param {number} minOpacity @param {number} maxOpacity
   * @param {number} periodMs
   * @param {{ phase? }} [opts]
   * @returns {{ update(elapsed: number): void }}
   */
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
  /**
   * Spin an element around a given point.
   *
   * @param {SVGElement} el
   * @param {number} cx @param {number} cy  pivot
   * @param {number} periodMs              full rotation time
   * @param {{ ccw? }} [opts]
   * @returns {{ update(elapsed: number): void }}
   */
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
  /**
   * Animate a sine-wave <path> d-attribute in real time.
   * The element must already exist in the SVG.
   *
   * @param {SVGPathElement} pathEl
   * @param {number} x0 @param {number} x1   start/end x
   * @param {number} cy                       vertical centre
   * @param {number} amplitude
   * @param {number} wavelength               pixels per cycle
   * @param {number} periodMs                 time for one full left-right scroll
   * @returns {{ update(elapsed: number): void }}
   */
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
