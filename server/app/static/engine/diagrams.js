/**
 * diagrams.js — High-level procedural diagram scene builders
 *
 * Each render function builds a complete scene inside the provided SVG and
 * registers all animations with the given DiagramEngine.
 *
 * Available renderers:
 *   Diagrams.atom(engine, svg, data)          — Bohr model with orbiting electrons
 *   Diagrams.solarSystem(engine, svg, data)   — Sun + orbiting planets
 *   Diagrams.wave(engine, svg, data)          — Animated sine / sound wave
 *   Diagrams.sun(engine, svg, data)           — Procedural sun with rays + glow
 *   Diagrams.plant(engine, svg, data)         — Simple branching plant
 *   Diagrams.flowArrow(engine, svg, data)     — Labelled linear flow diagram
 *
 * Descriptor shapes expected per type are described in each function's JSDoc.
 *
 * Requires: core.js, shapes.js, motion.js
 */

'use strict';

const Diagrams = (() => {

  // ── helpers ──────────────────────────────────────────────────────────────────

  /** Default canvas size */
  const W = 400, H = 300;
  const CX = W / 2, CY = H / 2;

  function g(attrs) { return svgEl('g', attrs || {}); }

  // Orbital period model: closer shells orbit faster (inverse-radius law)
  function _periodForRadius(r) { return 2000 + r * 18; }

  // Electron colours per shell
  const SHELL_COLORS = ['#4FC3F7', '#FF6B6B', '#81C784', '#FFB74D', '#CE93D8'];

  // ── atom ─────────────────────────────────────────────────────────────────────
  /**
   * Bohr-model atom diagram.
   *
   * Descriptor fields (all optional, sensible defaults):
   *   symbol      {string}   element symbol  default "H"
   *   protons     {number}                   default 1
   *   neutrons    {number}                   default 0
   *   shells      {number[]} electrons per shell e.g. [2,8,1]
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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
  /**
   * Solar system diagram.
   *
   * Descriptor fields (all optional):
   *   planets  {Array<{name, color?, radius?, orbitR?, periodMs?}>}
   *   showLabels {boolean} default true
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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
      const period  = p.periodMs ?? (1500 + i * 900);
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

      if (showLabels && p.name) {
        const labelEl = Shapes.text(0, 0, p.name, { color: 'label', size: 8 });
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
  /**
   * Animated sine wave with optional labels.
   *
   * Descriptor fields:
   *   label       {string}  wave label e.g. "Sound Wave"
   *   wavelength  {number}  pixels per cycle, default 80
   *   amplitude   {number}  default 40
   *   color       {string}  default 'secondary'
   *   showAxes    {boolean} default true
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
  function wave(engine, svg, data) {
    const amplitude  = data.amplitude  ?? 40;
    const wavelength = data.wavelength ?? 80;
    const color      = data.color      || 'secondary';
    const showAxes   = data.showAxes   !== false;
    const waveLabel  = data.label      || '';
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
  /**
   * A glowing sun with animated rotating rays.
   *
   * Descriptor fields:
   *   label  {string}  optional label below sun, default "Sun"
   *   rays   {number}  number of rays, default 12
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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
  /**
   * Simple branching plant.
   *
   * Descriptor fields:
   *   label   {string}  default "Plant"
   *   leaves  {number}  leaf pairs, default 2
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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
  /**
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   *   steps  {string[]}  step labels, max 5
   *   title  {string}    optional title
   */
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
  /**
   * Cyclical process: steps arranged in a ring with curved arrows.
   * Good for: water cycle, nitrogen cycle, life cycle, carbon cycle.
   *
   * Descriptor:
   *   title  {string}    optional heading
   *   steps  {string[]}  3-6 stage labels
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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
  /**
   * Central concept surrounded by labeled parts with dotted connectors.
   * Good for: cell biology, anatomy, labeled structures, force diagrams.
   *
   * Descriptor:
   *   title        {string}   optional heading
   *   center       {string}   center label, default "Cell"
   *   center_shape {string}   "circle" (default) | "rect"
   *   parts        {string[]} 2-8 part labels
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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
  /**
   * Two-column side-by-side comparison with bullet points.
   * Good for: Mitosis vs Meiosis, AC vs DC, Plant vs Animal cell.
   *
   * Descriptor:
   *   title        {string}   optional heading
   *   left         {string}   left column heading
   *   right        {string}   right column heading
   *   left_points  {string[]} up to 4 bullet points
   *   right_points {string[]} up to 4 bullet points
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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
  /**
   * Generic renderer: the LLM specifies individual SVG elements.
   * Useful for any concept that needs custom geometry.
   *
   * Descriptor:
   *   title    {string}  optional heading
   *   elements {Array}   element descriptors — each has:
   *     type   "path" | "circle" | "rect" | "text" | "line" | "arrow"
   *     d      SVG path data string   (type=path)
   *     cx,cy,r                       (type=circle)
   *     x,y,w,h                       (type=rect)
   *     x,y,text,size                 (type=text)
   *     x1,y1,x2,y2                   (type=line | arrow)
   *     color  "label"|"secondary"|"highlight"|"dim"|"green"|"gold"
   *     fill   "bg" | color token     (optional)
   *     sw     stroke-width           (optional, default 1.5)
   *     animate "pulse" | "fade"      (optional)
   *     delay  ms                     (optional)
   *
   * @param {DiagramEngine} engine
   * @param {SVGSVGElement} svg
   * @param {Object}        data
   */
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

  // ── Public API ───────────────────────────────────────────────────────────────
  return { atom, solarSystem, wave, sun, plant, flowArrow, cycle, labeled, comparison, custom };
})();

window.Diagrams = Diagrams;
