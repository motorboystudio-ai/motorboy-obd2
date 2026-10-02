// Lightweight SVG gauges + numeric tiles (no dependencies).
const SVG_NS = 'http://www.w3.org/2000/svg';

function el(tag, attrs = {}) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  return node;
}

function clamp(v, min, max) {
  return Math.min(max, Math.max(min, v));
}

/**
 * Semicircular gauge (240°→120° style half-arc).
 * cfg: { label, unit, min, max, redline, ticks, format }
 * Returns { set(value) }.
 */
export function createGauge(container, cfg) {
  container.innerHTML = '';
  const {
    label = '',
    unit = '',
    min = 0,
    max = 100,
    redline = null,
    ticks = 8,
    format = (v) => v.toFixed(0),
    labelFormat = null,
  } = cfg;

  const svg = el('svg', { viewBox: '0 0 200 122', class: 'gauge-svg' });
  const cx = 100;
  const cy = 104;
  const r = 84;

  const pt = (deg, rad = r) => [cx + rad * Math.cos((deg * Math.PI) / 180), cy - rad * Math.sin((deg * Math.PI) / 180)];
  const degFor = (v) => 180 - (180 * (clamp(v, min, max) - min)) / (max - min);

  // background arc
  const [x0, y0] = pt(180);
  const [x1, y1] = pt(0);
  svg.appendChild(el('path', { d: `M ${x0} ${y0} A ${r} ${r} 0 0 1 ${x1} ${y1}`, class: 'gauge-arc' }));

  // redline arc
  if (redline != null && redline < max) {
    const [rx, ry] = pt(degFor(redline));
    svg.appendChild(el('path', { d: `M ${rx} ${ry} A ${r} ${r} 0 0 1 ${x1} ${y1}`, class: 'gauge-redline' }));
  }

  // ticks + labels
  for (let i = 0; i <= ticks; i++) {
    const deg = 180 - (180 * i) / ticks;
    const value = min + ((max - min) * i) / ticks;
    const major = i % Math.max(1, Math.floor(ticks / 4)) === 0 || i === 0 || i === ticks;
    const [tx0, ty0] = pt(deg, r - 10);
    const [tx1, ty1] = pt(deg, r - (major ? 22 : 16));
    svg.appendChild(el('line', { x1: tx0, y1: ty0, x2: tx1, y2: ty1, class: major ? 'gauge-tick major' : 'gauge-tick' }));
    const valueLabel = labelFormat ? labelFormat(value) : String(Math.round(value));
    if (major || i % Math.ceil(ticks / 6) === 0) {
      const [lx, ly] = pt(deg, r - 34);
      const t = el('text', { x: lx, y: ly + 3, class: 'gauge-ticklabel', 'text-anchor': 'middle' });
      t.textContent = valueLabel;
      svg.appendChild(t);
    }
  }

  // needle
  const needle = el('line', { x1: cx, y1: cy, x2: cx, y2: cy, class: 'gauge-needle' });
  svg.appendChild(needle);
  svg.appendChild(el('circle', { cx, cy, r: 5.5, class: 'gauge-cap' }));

  // digital readout
  const valueText = el('text', { x: cx, y: 92, class: 'gauge-value', 'text-anchor': 'middle' });
  valueText.textContent = '—';
  svg.appendChild(valueText);
  const unitText = el('text', { x: cx, y: 108, class: 'gauge-unit', 'text-anchor': 'middle' });
  unitText.textContent = unit;
  svg.appendChild(unitText);
  const labelText = el('text', { x: cx, y: 120, class: 'gauge-label', 'text-anchor': 'middle' });
  labelText.textContent = label;
  svg.appendChild(labelText);

  container.appendChild(svg);

  let current = min;
  return {
    set(v) {
      current = v;
      const deg = degFor(v);
      const [nx, ny] = pt(deg, r - 18);
      needle.setAttribute('x2', nx);
      needle.setAttribute('y2', ny);
      valueText.textContent = Number.isFinite(v) ? format(v) : '—';
    },
    reset() {
      current = min;
      const deg = degFor(min);
      const [nx, ny] = pt(deg, r - 18);
      needle.setAttribute('x2', nx);
      needle.setAttribute('y2', ny);
      valueText.textContent = '—';
    },
    get value() {
      return current;
    },
  };
}

/** Numeric tile. cfg: { label, unit, format, signed }. Returns { set(value), reset() }. */
export function createTile(valueEl, cfg) {
  const { unit = '', format = (v) => v.toFixed(1), signed = false } = cfg;
  // Rebuild-safe: drop any unit span left over from a previous build.
  const stale = valueEl.parentElement.querySelector('.tile-unit');
  if (stale) stale.remove();
  const setUnit = document.createElement('span');
  setUnit.className = 'tile-unit';
  setUnit.textContent = unit;
  valueEl.parentElement.appendChild(setUnit);

  return {
    set(v) {
      if (!Number.isFinite(v)) {
        valueEl.textContent = '—';
        valueEl.classList.remove('neg');
        return;
      }
      let text;
      if (signed) text = (v > 0 ? '+' : '') + v.toFixed(2);
      else text = format(v);
      valueEl.textContent = text;
      valueEl.classList.toggle('neg', signed && v < 0);
    },
    reset() {
      valueEl.textContent = '—';
      valueEl.classList.remove('neg');
    },
    setUnit(u) {
      setUnit.textContent = u;
    },
  };
}
