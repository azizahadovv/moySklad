/* ============================================================
   viz.js — 🍱 Bento плиткалари учун график примитивлар (vanilla, build йўқ).
   Дизайн қоидалари (dataviz): ингичка марклар, 2px чизиқ, 4px юмалоқ уч,
   марклар орасида 2px сирт оралиғи, hairline тўр, ТАНЛАБ тўғридан-тўғри
   ёрлиқ, ҳар марк учун hover tooltip, матн ҳеч қачон серия рангида эмас.
   Ранглар CSS ўзгарувчиларидан (--s1..--s4) — light/dark бир жойда алмашади.
   Палитра validate_palette.js билан текширилган (light сирт #FFFFFF,
   dark сирт #17201C): CVD ΔE 9.1 light / 8.4 dark, нормал кўриш 22.9 / 19.8.
   Light'да --s3/--s4 контрасти 3:1 дан паст → БАРЧА бар қийматлари
   тўғридан-тўғри ёзилади (relief қоидаси).
   ============================================================ */

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export const vfmt = n => Math.round(Number(n) || 0).toLocaleString('ru-RU').replace(/,/g, ' ');

/* ---------------- tooltip (битта, ҳамма марк учун) ---------------- */
let tipEl;
function tipNode() {
  if (!tipEl) { tipEl = document.createElement('div'); tipEl.className = 'vz-tip'; tipEl.hidden = true; document.body.appendChild(tipEl); }
  return tipEl;
}
function showTip(html, x, y) {
  const t = tipNode();
  t.innerHTML = html; t.hidden = false;
  const r = t.getBoundingClientRect();
  t.style.left = Math.max(6, Math.min(window.innerWidth - r.width - 6, x - r.width / 2)) + 'px';
  t.style.top = (y - r.height - 12 < 6 ? y + 16 : y - r.height - 12) + 'px';
}
export function hideTip() { if (tipEl) tipEl.hidden = true; }

/** Бир марта уланади: [data-tip] бор ҳар қандай марк устида tooltip. */
let bound = false;
export function vizInit() {
  if (bound) return; bound = true;
  const move = e => {
    const m = e.target.closest?.('[data-tip]');
    if (!m) { hideTip(); return; }
    showTip(m.dataset.tip, e.clientX, e.clientY);
  };
  document.addEventListener('pointermove', move, { passive: true });
  document.addEventListener('pointerdown', move, { passive: true });
  document.addEventListener('pointerleave', hideTip, { passive: true });
  window.addEventListener('scroll', hideTip, { passive: true });
}

/* ---------------- 1. Горизонтал барлар ----------------
   items: [{label, value, color?, sub?}] · o: {max, fmt, unit}
   Ҳар барда қиймат ёзилади (relief), барлар орасида 2px оралиқ, уч 4px юмалоқ. */
export function barsH(items, o = {}) {
  if (!items || !items.length) return '<div class="vz-empty">маълумот йўқ</div>';
  const f = o.fmt || vfmt;
  const max = o.max ?? Math.max(1, ...items.map(i => Math.abs(Number(i.value) || 0)));
  return '<div class="vz-bars">' + items.map((i, n) => {
    const v = Number(i.value) || 0;
    const w = Math.max(2, Math.abs(v) / max * 100);
    const c = i.color || o.color || 'var(--s1)';   // битта серия — битта ранг
    return `<div class="vz-b" data-tip="${esc(i.label)}: <b>${esc(f(v))}</b>${i.sub ? '<br>' + esc(i.sub) : ''}">
      <span class="vz-l" title="${esc(i.label)}">${esc(i.label)}</span>
      <span class="vz-t"><span class="vz-f" style="width:${w}%;background:${c}"></span></span>
      <span class="vz-v num">${esc(f(v))}</span></div>`;
  }).join('') + '</div>';
}

/* ---------------- 2. Гуруҳланган барлар (2 серия, битта ўлчов) ----------------
   items: [{label, a, b, sub?}] · o: {aName, bName, fmt}
   Икки ўқ ҲЕЧ ҚАЧОН ишлатилмайди — иккала серия бир хил ўлчовда бўлиши шарт. */
export function barsGrouped(items, o = {}) {
  if (!items || !items.length) return '<div class="vz-empty">маълумот йўқ</div>';
  const f = o.fmt || vfmt;
  const max = Math.max(1, ...items.flatMap(i => [Math.abs(i.a || 0), Math.abs(i.b || 0)]));
  const leg = `<div class="vz-leg"><span><i style="background:var(--s1)"></i>${esc(o.aName || 'A')}</span><span><i style="background:var(--s2)"></i>${esc(o.bName || 'B')}</span></div>`;
  return leg + '<div class="vz-bars g">' + items.map(i => `
    <div class="vz-g">
      <span class="vz-l" title="${esc(i.label)}">${esc(i.label)}</span>
      <span class="vz-gt">
        <span class="vz-t" data-tip="${esc(i.label)} · ${esc(o.aName || 'A')}: <b>${esc(f(i.a))}</b>"><span class="vz-f" style="width:${Math.max(2, Math.abs(i.a || 0) / max * 100)}%;background:var(--s1)"></span></span>
        <span class="vz-t" data-tip="${esc(i.label)} · ${esc(o.bName || 'B')}: <b>${esc(f(i.b))}</b>"><span class="vz-f" style="width:${Math.max(2, Math.abs(i.b || 0) / max * 100)}%;background:var(--s2)"></span></span>
      </span>
      <span class="vz-v num">${esc(f(i.a))}<small>${esc(f(i.b))}</small></span>
    </div>`).join('') + '</div>';
}

/* ---------------- 3. Метр (бир кўрсаткич, чегара билан) ----------------
   Тўлдириш ҳолатни билдиради (accent → warn → bad), трек — ўша рампанинг очиқ қадами. */
export function meter(pct, o = {}) {
  const p = Math.max(0, Math.min(100, Number(pct) || 0));
  const bad = o.badBelow ?? 0, warn = o.warnBelow ?? 0;
  const state = p < bad ? 'bad' : p < warn ? 'warn' : 'ok';
  return `<div class="vz-meter ${state}" data-tip="${esc(o.label || '')}: <b>${p}%</b>${o.target ? '<br>мақсад ' + o.target + '%' : ''}">
    <span class="vz-mt"><span class="vz-mf" style="width:${p}%"></span>${o.target ? `<i class="vz-mk" style="left:${Math.min(100, o.target)}%"></i>` : ''}</span>
    <span class="vz-mv num">${p}%</span></div>`;
}

/* ---------------- 4. Спарклайн (12 нуқта, ўқсиз) ---------------- */
export function sparkline(vals, o = {}) {
  const v = (vals || []).map(x => Number(x) || 0);
  if (v.length < 2) return '<div class="vz-empty">тарих йўқ</div>';
  const w = 100, h = o.h || 28, min = Math.min(...v), max = Math.max(...v), rng = max - min || 1;
  const pt = i => [i / (v.length - 1) * w, h - 2 - (v[i] - min) / rng * (h - 4)];
  const d = v.map((_, i) => (i ? 'L' : 'M') + pt(i).map(n => n.toFixed(1)).join(' ')).join(' ');
  const last = pt(v.length - 1);
  return `<svg class="vz-spark" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true">
    <path d="${d}" fill="none" stroke="${o.color || 'var(--s1)'}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
    <circle cx="${last[0].toFixed(1)}" cy="${last[1].toFixed(1)}" r="2.6" fill="${o.color || 'var(--s1)'}" stroke="var(--surface)" stroke-width="2" vector-effect="non-scaling-stroke"/>
  </svg>`;
}

/* ---------------- 5. Чизиқли график (вақт бўйича, crosshair + tooltip) ----------------
   points: [{x: 'ёрлиқ', y: сон}] · o: {fmt, color, h, unit}
   Фақат охирги нуқта тўғридан-тўғри ёрлиқланади; қолганини tooltip кўрсатади. */
export function lineChart(points, o = {}) {
  const p = (points || []).filter(x => x && x.y !== null && x.y !== undefined);
  if (p.length < 2) return '<div class="vz-empty">тарих етарли эмас (камида 2 нуқта)</div>';
  const f = o.fmt || vfmt;
  const W = 300, H = o.h || 96, PL = 6, PR = 34, PT = 10, PB = 16;
  const ys = p.map(x => Number(x.y) || 0);
  let min = Math.min(...ys), max = Math.max(...ys);
  if (o.from0) min = Math.min(0, min);
  if (max === min) { max = min + 1; }
  const px = i => PL + i / (p.length - 1) * (W - PL - PR);
  const py = v => PT + (1 - (v - min) / (max - min)) * (H - PT - PB);
  const d = p.map((x, i) => (i ? 'L' : 'M') + px(i).toFixed(1) + ' ' + py(Number(x.y) || 0).toFixed(1)).join(' ');
  const grid = [0, 0.5, 1].map(t => {
    const y = PT + t * (H - PT - PB);
    return `<line x1="${PL}" y1="${y.toFixed(1)}" x2="${W - PR}" y2="${y.toFixed(1)}" stroke="var(--line)" stroke-width="1" vector-effect="non-scaling-stroke"/>`;
  }).join('');
  const dots = p.map((x, i) => `<circle class="vz-pt" cx="${px(i).toFixed(1)}" cy="${py(Number(x.y) || 0).toFixed(1)}" r="8" fill="transparent" data-tip="${esc(x.x)}: <b>${esc(f(x.y))}${o.unit ? ' ' + esc(o.unit) : ''}</b>"/>`).join('');
  const lastY = py(Number(p[p.length - 1].y) || 0);
  return `<svg class="vz-line" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
    ${grid}
    <path d="${d}" fill="none" stroke="${o.color || 'var(--s1)'}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
    <circle cx="${px(p.length - 1).toFixed(1)}" cy="${lastY.toFixed(1)}" r="3" fill="${o.color || 'var(--s1)'}" stroke="var(--surface)" stroke-width="2" vector-effect="non-scaling-stroke"/>
    ${dots}
  </svg>
  <div class="vz-ax"><span>${esc(p[0].x)}</span><b class="num">${esc(f(p[p.length - 1].y))}${o.unit ? ' ' + esc(o.unit) : ''}</b><span>${esc(p[p.length - 1].x)}</span></div>`;
}

/* ---------------- 6. Ҳолат чиплари (статус — ранг + белги + ёрлиқ) ----------------
   Статус ранглари фақат ҳолат учун, серия ранги сифатида ҳеч қачон ишлатилмайди. */
export function statChips(items) {
  return '<div class="vz-chips">' + (items || []).map(i =>
    `<span class="vz-chip ${i.state || ''}" ${i.go ? `data-go="${esc(i.go)}"` : ''}><i>${i.icon || '●'}</i>${esc(i.label)}<b class="num">${esc(i.value)}</b></span>`).join('') + '</div>';
}
