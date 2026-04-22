/**
 * shapes.js — Procedural SVG shape generators
 *
 * All functions return an SVGElement (or group) that can be appended to
 * an SVG container.  They do NOT animate — motion.js handles that.
 *
 * Requires core.js to be loaded first (svgEl, setAttrs, append, resolveColor).
 *
 * Exported on window:
 *   Shapes.circle, Shapes.ellipse, Shapes.line, Shapes.dottedLine,
 *   Shapes.path, Shapes.text, Shapes.label, Shapes.arc,
 *   Shapes.arrow, Shapes.curvedArrow,
 *   Shapes.rect, Shapes.roundedRect, Shapes.polygon,
 *   Shapes.radialLines, Shapes.grid,
 *   Shapes.angleArc, Shapes.tick
 */

'use strict';

const Shapes = (() => {

  // ── Defaults ────────────────────────────────────────────────────────────────
  const DEF_STROKE  = () => resolveColor('stroke');
  const DEF_LABEL   = () => resolveColor('label');
  const DEF_SW      = 1.5;

  // ── circle ──────────────────────────────────────────────────────────────────
  /**
   * @param {number} cx @param {number} cy @param {number} r
   * @param {{ color?, fill?, sw?, opacity?, glow?, id? }} [opts]
   */
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
  /**
   * @param {number} cx @param {number} cy @param {number} rx @param {number} ry
   * @param {{ color?, fill?, sw?, opacity?, rotate?, id? }} [opts]
   */
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
  /**
   * @param {number} x1 @param {number} y1 @param {number} x2 @param {number} y2
   * @param {{ color?, sw?, dash?, opacity? }} [opts]
   */
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

  /** Convenience: dashed line */
  function dottedLine(x1, y1, x2, y2, opts = {}) {
    return line(x1, y1, x2, y2, { dash: '4 3', sw: 1, ...opts });
  }

  // ── path ────────────────────────────────────────────────────────────────────
  /**
   * @param {string} d   SVG path data
   * @param {{ color?, fill?, sw?, opacity?, dash? }} [opts]
   */
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
  /**
   * @param {number} x @param {number} y @param {string} content
   * @param {{ color?, size?, anchor?, weight?, opacity? }} [opts]
   */
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

  /** Shorthand: chalk-yellow label */
  function label(x, y, content, opts = {}) {
    return text(x, y, content, { color: 'label', size: 11, ...opts });
  }

  // ── arc ─────────────────────────────────────────────────────────────────────
  /**
   * Circular arc (partial circle path).
   * @param {number} cx @param {number} cy @param {number} r
   * @param {number} startDeg @param {number} endDeg  (0 = right, clockwise)
   * @param {{ color?, sw?, opacity? }} [opts]
   */
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
  /**
   * @param {number} x1 @param {number} y1 @param {number} x2 @param {number} y2
   * @param {{ color?, sw? }} [opts]
   */
  function arrow(x1, y1, x2, y2, opts = {}) {
    return svgEl('line', {
      x1, y1, x2, y2,
      stroke:           resolveColor(opts.color) || resolveColor('secondary'),
      'stroke-width':   opts.sw ?? 1.5,
      'marker-end':     'url(#arrow)',
    });
  }

  // ── curvedArrow (quadratic bezier) ──────────────────────────────────────────
  /**
   * @param {number} x1 @param {number} y1 @param {number} x2 @param {number} y2
   * @param {number} cpx @param {number} cpy  control point
   * @param {{ color?, sw? }} [opts]
   */
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
  /**
   * @param {number} x @param {number} y @param {number} w @param {number} h
   * @param {{ color?, fill?, sw?, rx?, opacity? }} [opts]
   */
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

  /** Convenience: rounded rect */
  function roundedRect(x, y, w, h, opts = {}) {
    return rect(x, y, w, h, { rx: 6, ...opts });
  }

  // ── polygon ──────────────────────────────────────────────────────────────────
  /**
   * @param {Array<[number,number]>} points  array of [x,y]
   * @param {{ color?, fill?, sw?, opacity? }} [opts]
   */
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
  /**
   * Draw N evenly-spaced radial lines from (cx,cy) spanning r1→r2.
   * @param {number} cx @param {number} cy
   * @param {number} r1  inner radius @param {number} r2  outer radius
   * @param {number} n   number of lines
   * @param {{ color?, sw?, startAngle? }} [opts]
   * @returns {SVGGElement}
   */
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
  /**
   * Draw a grid of horizontal and vertical lines in a bounding box.
   * @param {number} x @param {number} y @param {number} w @param {number} h
   * @param {number} cols @param {number} rows
   * @param {{ color?, sw? }} [opts]
   * @returns {SVGGElement}
   */
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
  /**
   * @param {number} vx @param {number} vy  vertex
   * @param {number} angle1Deg @param {number} angle2Deg
   * @param {number} r  radius of the arc
   * @param {{ color?, sw? }} [opts]
   */
  function angleArc(vx, vy, angle1Deg, angle2Deg, r, opts = {}) {
    return arc(vx, vy, r, angle1Deg, angle2Deg, { sw: 1.2, ...opts });
  }

  // ── axis tick mark ───────────────────────────────────────────────────────────
  /**
   * @param {number} x @param {number} y @param {boolean} horizontal
   * @param {number} size  half-length of tick
   * @param {{ color?, sw? }} [opts]
   */
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
