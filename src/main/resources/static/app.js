/* ============================================================
   Касса Назорати — Админ панел (Telegram Mini App)
   Тузилма: api → util → router → pages. Кутубхонасиз, бир файл.
   Route қоидаси: #/<tab>/<объект>/<кўриниш>, ID йўлда, фильтр сўровда.
   ============================================================ */

const tg = window.Telegram?.WebApp;
/* initData: SDK'dan; SDK yuklanmagan bo'lsa Telegram URL hash'iga qo'ygan tgWebAppData'dan
   (hash'ni router o'zgartirmasidan OLDIN o'qib olamiz). */
const INIT = tg?.initData || (() => {
  try {
    const h = location.hash.startsWith('#') ? location.hash.slice(1) : location.hash;
    const v = new URLSearchParams(h).get('tgWebAppData') || sessionStorage.getItem('kn.initData') || '';
    if (v) sessionStorage.setItem('kn.initData', v);
    return v;
  } catch (_) { return ''; }
})();
if (location.hash.includes('tgWebAppData')) history.replaceState(null, '', location.pathname);   // Telegram parametrlari route emas
const $main = document.getElementById('main');

/* ---------------------------- API ---------------------------- */
async function api(path, opts = {}) {
  const res = await fetch('/api' + path, {
    ...opts,
    headers: { 'X-Telegram-Init-Data': INIT, 'Content-Type': 'application/json', ...(opts.headers || {}) },
  });
  if (!res.ok) {
    let msg = res.status === 401 ? 'Рухсат йўқ — иловани фақат Telegram ичидан очинг'
            : res.status === 403 ? 'Админ панел фақат бухгалтер ва админ учун' : 'Хатолик ' + res.status;
    try { const j = await res.json(); if (j.error) msg = j.error; } catch (_) { /* матн эмас */ }
    throw new Error(msg);
  }
  return res.json();
}

/* ---------------------------- util ---------------------------- */
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = n => Math.round(Number(n) || 0).toLocaleString('ru-RU').replace(/,/g, ' ');
const fmtT = t => (Number(t) / 100).toLocaleString('ru-RU', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
const sign = n => (n > 0 ? '+' : '') + fmt(n);
const OYLAR = ['январ', 'феврал', 'март', 'апрел', 'май', 'июн', 'июл', 'август', 'сентябр', 'октябр', 'ноябр', 'декабр'];
const dUz = iso => { const d = new Date(iso + 'T00:00:00'); return d.getDate() + ' ' + OYLAR[d.getMonth()]; };
const dShort = iso => iso.slice(8, 10) + '.' + iso.slice(5, 7);
const holatCls = h => h === 'teng' ? 'ok' : h === 'farq' ? 'warn' : 'bad';
const HOLAT = { teng: '✅ тенг', farq: '⚠️ фарқ', kiritilmagan: '❗ киритилмаган' };

function toast(text) {
  const t = document.createElement('div'); t.className = 'toast'; t.textContent = text;
  document.body.appendChild(t); setTimeout(() => t.remove(), 2200);
}
function haptic(kind = 'light') { try { tg?.HapticFeedback?.impactOccurred(kind); } catch (_) { /* эски клиент */ } }
const TAB_NAME = { bugun: '🏠 Бугун', kassa: '🏪 Кассалар', hisobot: '📊 Ҳисоботлар', sozlama: '⚙️ Созламалар' };
/** Сарлавҳа + изоҳ; йўл (crumbs) route'дан автоматик: Таб › бўлим › саҳифа */
function setTitle(title, sub, crumbs) {
  document.getElementById('title').textContent = title;
  document.getElementById('subtitle').textContent = sub || '';
  const c = document.getElementById('crumbs');
  if (crumbs === undefined) { const { seg } = parseRoute(); crumbs = crumbsOf(seg); }
  c.innerHTML = crumbs ? crumbs.map((x, i, a) => i === a.length - 1 ? `<b>${esc(x)}</b>` : esc(x)).join(' › ') : '';
  c.hidden = !crumbs || crumbs.length < 2;
}
const CRUMB = { pending: 'Кутилаётган', kunlik: 'Кунлик солиштириш', tushum: 'Тушум', karta: 'Карталар', tarix: 'История', excel: 'Excel', pul: 'Пул ҳаракати',
  menyu: 'Меню тартиби', sxema: 'Бот сxемаси', nomlar: 'Тугма номлари', huquq: 'Ҳуқуқлар', shablon: 'Билдиришномалар', namuna: 'Намуналар', yordam: 'Ўринбосарлар',
  xodim: 'Ходимлар', guruh: 'Гуруҳлар', moliya: 'Молия', init: 'Бошланғич қолдиқ', adjust: 'Корректировка', ledger: 'Ledger санаси', zero: 'Нол бошлаш',
  moysklad: 'MoySklad', token: 'API калити', names: 'Номлар', diag: 'Диагностика', reload: 'Қайта юклаш', audit: 'Аудит', user: 'Ходим' };
function crumbsOf(seg) {
  const root = seg[0] || '';
  const tab = root === '' || root === 'pending' ? 'bugun' : root;
  const out = [TAB_NAME[tab]];
  const rest = root === 'pending' ? seg : seg.slice(1);
  for (const x of rest) out.push(CRUMB[x] || (/^\d+$/.test(x) ? '#' + x : x));
  return out;
}
function html(strings, ...vals) { return strings.reduce((a, s, i) => a + s + (vals[i] ?? ''), ''); }

/* bottom sheet: confirm/форма */
function sheet(inner, onMount) {
  const bg = document.createElement('div'); bg.className = 'sheet-bg';
  bg.innerHTML = `<div class="sheet"><div class="grab"></div>${inner}</div>`;
  bg.addEventListener('click', e => { if (e.target === bg) close(); });
  document.body.appendChild(bg);
  function close() { bg.remove(); }
  onMount?.(bg, close);
  return close;
}

/* ---------------------------- router ---------------------------- */
let ME = null;
const state = { tab: 'bugun' };

function go(hash) { location.hash = hash; }
function back() { if (history.length > 1) history.back(); else go('#/'); }

function parseRoute() {
  const h = location.hash.replace(/^#\/?/, '');
  const [path, qs] = h.split('?');
  const seg = path.split('/').filter(Boolean);
  const q = Object.fromEntries(new URLSearchParams(qs || ''));
  return { seg, q };
}

const PAGES = {
  '': pageBugun,
  'pending': pagePending,
  'kassa': pageKassa,
  'hisobot': pageHisobot,
  'sozlama': pageSozlama,
};

async function render() {
  const { seg, q } = parseRoute();
  const root = seg[0] || '';
  state.tab = root === '' || root === 'pending' ? 'bugun' : root;
  document.querySelectorAll('.nav button').forEach(b => b.classList.toggle('on', b.dataset.tab === state.tab));
  const isRoot = seg.length === 0 || (seg.length === 1 && root !== 'pending');
  document.getElementById('back').hidden = isRoot;
  try { isRoot ? tg?.BackButton?.hide() : tg?.BackButton?.show(); } catch (_) { /* */ }
  try { tg?.MainButton?.hide(); } catch (_) { /* */ }
  $main.innerHTML = '<div class="skel"></div><div class="skel"></div><div class="skel"></div>';
  window.scrollTo(0, 0);
  const page = PAGES[root] || pageBugun;
  try { await page(seg.slice(1), q); }
  catch (e) { $main.innerHTML = `<div class="err">${esc(e.message)}</div>`; }
}

/* ============================================================
   🏠 БУГУН — dashboard
   ============================================================ */
async function pageBugun() {
  const d = await api('/admin/dashboard');
  setTitle('Бугун · ' + dUz(d.today), 'ҳолат ' + d.asOf + (d.msKnown ? '' : ' · ⚠️ MoySklad ўқилмади'));
  document.getElementById('dot-bugun').hidden = d.pending.length === 0;
  const farqli = d.kassalar.filter(k => !k.cashless && k.farq !== 0);
  const kartaBad = d.karta.farq + d.karta.kiritilmagan;

  // 1) Ҳолат — тўрт рақам
  let h = `<div class="label">Ҳолат</div><div class="kpis">
      <div class="kpi"><div class="l">💵 Нақд кассаларда</div><div class="v num">${fmt(d.naqdJami)}</div><div class="s">бухгалтерияда ${fmt(d.buxNaqd)}</div></div>
      <div class="kpi"><div class="l">📲 Click карталарда</div><div class="v num">${fmtT(d.karta.kartaTiyin)}</div><div class="s">MoySklad ${fmtT(d.karta.msTiyin)}</div></div>
      <div class="kpi link" data-go="#/pending"><div class="l">📥 Қарор кутмоқда</div><div class="v num ${d.pending.length ? 'bad' : 'good'}">${d.pending.length}</div><div class="s">${d.pending.length ? 'ҳисобот' : 'ҳаммаси кўрилган'}</div></div>
      <div class="kpi link" data-go="#/hisobot/karta"><div class="l">💳 Карта фарқи</div><div class="v num ${kartaBad ? 'warn' : 'good'}">${kartaBad}</div><div class="s">${d.karta.teng} тенг · ${d.karta.farq} фарқ · ${d.karta.kiritilmagan} йўқ</div></div>
    </div>`;

  // 2) Қарор кутмоқда / эътибор — фақат бўлса
  const attention = [];
  for (const p of d.pending) attention.push(rowHtml('bad', p.kassa, `ҳисобот #${p.id} · ${p.days} кун · ${p.kassir || ''}`, fmt(p.naqd) + '<small>нақд</small>', `#/pending/${p.id}`));
  for (const k of farqli) attention.push(rowHtml('warn', k.name, `MoySklad ${fmt(k.savdoMs)} · бот ${fmt(k.savdoBot)}`, sign(k.farq) + '<small>фарқ</small>', `#/kassa/${k.id}`));
  for (const k of d.kassalar.filter(k => k.karta.kiritilmagan > 0)) attention.push(rowHtml('warn', k.name, `${k.karta.kiritilmagan} та карта қолдиғи киритилмаган`, '❗', `#/kassa/${k.id}`));
  if (attention.length) h += `<div class="label">Эътибор керак · ${attention.length}</div><div class="rows">${attention.join('')}</div>`;

  // 3) Тез амаллар
  h += `<div class="label">Тез амаллар</div><div class="tiles">
      ${tile('💰', 'Пул қабул қилиш', 'кассани танланг', '#/kassa')}
      ${tile('📋', 'Кунлик солиштириш', 'MoySklad = бот?', '#/hisobot/kunlik')}
      ${tile('💵', 'Пул ҳаракати', 'топширилган · қабул', '#/hisobot/pul')}
      ${tile('📊', 'Excel', 'файл чатга', '#/hisobot/excel')}
    </div>`;

  // 4) Кассалар бугун
  h += `<div class="label">Кассалар бугун</div><div class="rows">`;
  for (const k of d.kassalar.filter(k => !k.cashless))
    h += rowHtml(k.pending ? 'bad' : k.farq === 0 ? 'ok' : 'warn', k.name, `савдо: MoySklad ${fmt(k.savdoMs)} · бот ${fmt(k.savdoBot)}${k.openDays ? ' · ' + k.openDays + ' кун топширилмаган' : ''}`, fmt(k.naqd) + '<small>нақд қўлда</small>', `#/kassa/${k.id}`);
  h += `</div>`;
  $main.innerHTML = h;
  bindGo();
}

function rowHtml(cls, title, sub, right, href) {
  const tag = href ? 'button' : 'div';
  return `<${tag} class="row ${cls} ${href ? 'tap' : ''}" ${href ? `data-go="${href}"` : ''}>
    <div class="t"><b>${esc(title)}</b><span>${esc(sub)}</span></div>
    <div class="n num">${right}</div>${href ? '<span class="chev">›</span>' : ''}</${tag}>`;
}
/** Бўлимга кириш плиткаси: белги, ном, бир қатор изоҳ. */
function tile(icon, title, sub, href, cls = '') {
  return `<button class="tile ${cls}" data-go="${href}"><span class="i">${icon}</span><b>${esc(title)}</b><span>${esc(sub)}</span></button>`;
}
function bindGo() {
  $main.querySelectorAll('[data-go]').forEach(el => el.addEventListener('click', () => { haptic(); go(el.dataset.go); }));
}

/* ============================================================
   📥 КУТИЛАЁТГАН ҲИСОБОТ — рўйхат ва қарор
   ============================================================ */
async function pagePending(seg) {
  if (!seg[0]) {
    const d = await api('/admin/dashboard');
    setTitle('Кутилаётган ҳисоботлар', d.pending.length + ' та');
    $main.innerHTML = d.pending.length
      ? `<div class="rows">${d.pending.map(p => rowHtml('bad', p.kassa, `#${p.id} · ${p.days} кун · ${p.createdAt} · ${p.kassir}`, fmt(p.naqd) + '<small>нақд · click ' + fmt(p.klik) + '</small>', `#/pending/${p.id}`)).join('')}</div>`
      : `<div class="card"><div class="empty">Кутилаётган ҳисобот йўқ.</div></div>`;
    bindGo();
    return;
  }
  const p = await api('/admin/pending/' + seg[0]);
  const open = p.status === 'KUTILMOQDA';
  setTitle('Ҳисобот #' + p.id, p.kassa + ' · ' + p.createdAt);
  let h = html`
    <div class="card">
      <div class="kv">
        <dt>Касса</dt><dd>${esc(p.kassa)}</dd>
        <dt>Кассир</dt><dd>${esc(p.kassir || '—')}</dd>
        <dt>Ҳолат</dt><dd>${open ? '<span class="pill bad">кутмоқда</span>' : '<span class="pill muted">' + esc(p.status) + '</span>'}</dd>
        ${p.msKnown && p.bugunFarq !== 0 ? `<dt>Бугунги фарқ (MoySklad − бот)</dt><dd style="color:var(--warn)">${sign(p.bugunFarq)}</dd>` : ''}
      </div>
    </div>
    <div class="label">Кунлар</div>
    <div class="tablewrap"><table>
      <thead><tr><th>Кун</th><th class="num">Нақд</th><th class="num">Click</th><th class="num">Терминал</th><th class="num">Расход</th><th class="num">Қолди нақд</th><th class="num">Қолди click</th></tr></thead>
      <tbody>${p.days.map(x => `<tr><td>${dShort(x.date)}</td><td class="num">${fmt(x.prixodNaqd)}</td><td class="num">${fmt(x.prixodKlik)}</td><td class="num">${fmt(x.prixodTerminal)}</td><td class="num">${fmt(x.rasxodNaqd + x.rasxodKlik)}</td><td class="num">${fmt(x.remainNaqd)}</td><td class="num">${fmt(x.remainKlik)}</td></tr>`).join('')}
      <tr class="total"><td>Жами</td><td></td><td></td><td></td><td></td><td class="num">${fmt(p.naqd)}</td><td class="num">${fmt(p.klik)}</td></tr></tbody>
    </table></div>`;
  if (open) h += html`
    <div class="label">Қарор</div>
    <div class="actions">
      <button class="btn main" id="okFull">✅ Тўлиқ қабул қилиш</button>
      <button class="btn ghost" id="okPart">✂️ Қисман қабул қилиш</button>
      <button class="btn danger" id="rej">✖ Рад этиш</button>
    </div>`;
  else h += `<div class="card"><div class="kv"><dt>Қабул қилинган нақд</dt><dd>${fmt(p.acceptedNaqd)}</dd><dt>Қабул қилинган click</dt><dd>${fmt(p.acceptedKlik)}</dd>${p.comment ? `<dt>Изоҳ</dt><dd>${esc(p.comment)}</dd>` : ''}</div></div>`;
  $main.innerHTML = h;
  if (!open) return;

  document.getElementById('okFull').onclick = () => confirmSheet(
    'Тўлиқ қабул қилиш', `Нақд <b>${fmt(p.naqd)}</b> ва click <b>${fmt(p.klik)}</b> сўм бухгалтерияга ўтади, кунлар ёпилади.`,
    () => decide(p.id, { action: 'approve' }));
  document.getElementById('okPart').onclick = () => sheet(html`
    <h2>✂️ Қисман қабул қилиш</h2>
    <div class="field"><label>Нақд (сўм, макс ${fmt(p.naqd)})</label><input id="pn" type="number" inputmode="numeric" value="${p.naqd}"></div>
    <div class="field"><label>Click (сўм, макс ${fmt(p.klik)})</label><input id="pk" type="number" inputmode="numeric" value="${p.klik}"></div>
    <button class="btn main" id="ok">Қабул қилиш</button>`, (el, close) => {
      el.querySelector('#ok').onclick = () => {
        const naqd = +el.querySelector('#pn').value, klik = +el.querySelector('#pk').value;
        if (naqd < 0 || klik < 0 || naqd > p.naqd || klik > p.klik) { toast('Сумма 0 дан кўп ва ҳисоботдан ошмасин'); return; }
        close(); decide(p.id, { action: 'partial', naqd, klik });
      };
    });
  document.getElementById('rej').onclick = () => sheet(html`
    <h2>✖ Рад этиш</h2>
    <div class="field"><label>Сабаб (кассирга боради)</label><textarea id="rs" rows="3" placeholder="Масалан: 03.09 нақд суммаси нотўғри"></textarea></div>
    <button class="btn danger" id="ok">Рад этиш</button>`, (el, close) => {
      el.querySelector('#ok').onclick = () => {
        const reason = el.querySelector('#rs').value.trim();
        if (reason.length < 3) { toast('Сабабни ёзинг'); return; }
        close(); decide(p.id, { action: 'reject', reason });
      };
    });
}

function confirmSheet(title, body, onOk) {
  sheet(`<h2>${title}</h2><p>${body}</p><button class="btn main" id="ok">Тасдиқлайман</button><button class="btn ghost" id="no">Бекор</button>`,
    (el, close) => { el.querySelector('#ok').onclick = () => { close(); onOk(); }; el.querySelector('#no').onclick = close; });
}

async function decide(id, body) {
  try {
    await api('/decide', { method: 'POST', body: JSON.stringify({ kind: 'submission', id, ...body }) });
    haptic('medium');
    toast(body.action === 'approve' ? '✅ Қабул қилинди' : body.action === 'partial' ? '🟡 Қисман қабул қилинди' : '❌ Рад этилди');
    go('#/pending');
  } catch (e) { toast(e.message); }
}

/* ============================================================
   🏪 КАССАЛАР — рўйхат ва карта
   ============================================================ */
async function pageKassa(seg, q = {}) {
  if (!seg[0]) {
    const d = await api('/admin/dashboard');
    setTitle('Кассалар', d.kassalar.length + ' та · ҳолат ' + d.asOf);
    $main.innerHTML = `<div class="rows">${d.kassalar.map(k => {
      const cls = k.pending ? 'bad' : (!k.cashless && k.farq !== 0) || k.karta.farq || k.karta.kiritilmagan ? 'warn' : 'ok';
      const sub = [k.pending ? `${k.pending} ҳисобот кутмоқда` : null, k.openDays ? `${k.openDays} кун топширилмаган` : null,
        k.karta.soni ? `карта ${k.karta.teng}/${k.karta.soni} тенг` : null].filter(Boolean).join(' · ') || 'жойида';
      return rowHtml(cls, k.name, sub, fmt(k.naqd) + '<small>нақд · click ' + fmt(k.klik) + '</small>', `#/kassa/${k.id}`);
    }).join('')}</div>`;
    bindGo();
    return;
  }
  const k = await api('/admin/kassa/' + seg[0]);
  const tab = q.t || 'umumiy';
  setTitle(k.name, (k.label ? k.label + ' · ' : '') + (k.cashless ? 'нақдсиз' : 'нақд касса'));
  const t = k.today || {};
  const tabs = [['umumiy', 'Умумий'], ['kunlar', `Кунлар${k.openDays.length ? ' · ' + k.openDays.length : ''}`], ['kartalar', `Карталар${k.kartalar.length ? ' · ' + k.kartalar.length : ''}`], ['amallar', 'Амаллар']];
  let h = `<div class="seg">${tabs.map(([id, l]) => `<button class="${tab === id ? 'on' : ''}" data-go="#/kassa/${k.id}?t=${id}">${l}</button>`).join('')}</div>`;
  if (k.pending.length) {
    h += `<div class="label">Қарор кутмоқда</div><div class="rows">`;
    for (const p of k.pending) h += rowHtml('bad', `Ҳисобот #${p.id}`, `${p.days} кун · ${p.createdAt} · ${p.kassir}`, fmt(p.naqd) + '<small>нақд · click ' + fmt(p.klik) + '</small>', `#/pending/${p.id}`);
    h += `</div>`;
  }
  if (tab === 'umumiy') {
    h += html`<div class="label">Ҳолат</div><div class="kpis">
      <div class="kpi"><div class="l">MoySklad савдо</div><div class="v num">${fmt(k.savdoMs)}</div><div class="s">${k.msKnown ? 'бугун' : '⚠️ ўқилмади'}</div></div>
      <div class="kpi"><div class="l">Бот савдо</div><div class="v num ${k.farq === 0 ? '' : 'warn'}">${fmt(k.savdoBot)}</div><div class="s">${k.farq === 0 ? '✅ мос' : 'фарқ ' + sign(k.farq)}</div></div>
      <div class="kpi"><div class="l">💵 Нақд қўлда</div><div class="v num">${fmt(k.naqd)}</div><div class="s">банд ${fmt(k.naqdBand)} · мавжуд ${fmt(k.naqdMavjud)}</div></div>
      <div class="kpi"><div class="l">📲 Click</div><div class="v num">${fmt(k.klik)}</div><div class="s">${k.openDays.length ? k.openDays.length + ' кун топширилмаган' : 'ҳаммаси топширилган'}</div></div>
    </div>
    <div class="label">Бугунги ҳаракат</div>
    <div class="card"><div class="kv">
      <dt>Приход нақд</dt><dd>${fmt(t.prixodNaqd)}</dd>
      <dt>Приход click</dt><dd>${fmt(t.prixodKlik)}</dd>
      <dt>Приход терминал</dt><dd>${fmt(t.prixodTerminal)}</dd>
      <dt>Возврат</dt><dd>${fmt((t.vozvratNaqd || 0) + (t.vozvratKlik || 0))}</dd>
      <dt>Расход</dt><dd>${fmt((t.rasxodNaqd || 0) + (t.rasxodKlik || 0))}</dd>
    </div></div>
    <div class="label">Ходимлар</div><div class="card">${k.kassirs.length ? k.kassirs.map(u => `<div>${esc(u.name)} <span class="hint">· ${u.role === 'KASSIR' ? 'кассир' : u.role.toLowerCase()}${u.tgId ? '' : ' · Telegram уланмаган'}</span></div>`).join('') : '<div class="empty">Ходим бириктирилмаган</div>'}</div>`;
  }
  if (tab === 'kunlar') {
    h += `<div class="label">Топширилмаган кунлар</div>`;
    h += k.openDays.length ? `<div class="tablewrap"><table><thead><tr><th>Кун</th><th class="num">Нақд</th><th class="num">Click</th><th>Ҳолат</th></tr></thead><tbody>${k.openDays.map(x => `<tr><td>${dShort(x.date)}</td><td class="num">${fmt(x.naqd)}</td><td class="num">${fmt(x.klik)}</td><td>${x.status === 'OCHIQ' ? 'очиқ' : 'ёпилган'}</td></tr>`).join('')}<tr class="total"><td>Жами</td><td class="num">${fmt(k.openDays.reduce((a, x) => a + x.naqd, 0))}</td><td class="num">${fmt(k.openDays.reduce((a, x) => a + x.klik, 0))}</td><td></td></tr></tbody></table></div>`
      : `<div class="card"><div class="empty">Ҳамма кунлар топширилган.</div></div>`;
    h += `<div class="hint">Кассир «📤 Hisobot topshirish» орқали кунларни ҳисоботга йиғади; бухгалтер уни қабул қилади ёки «💰 Пул қабул қилиш» билан бевосита олади.</div>`;
  }
  if (tab === 'kartalar') {
    h += `<div class="label">Click карталар</div>`;
    h += k.kartalar.length ? `<div class="rows">${k.kartalar.map(c => rowHtml(holatCls(c.holat), c.name, c.holat === 'kiritilmagan' ? 'қолдиқ киритилмаган' : `карта ${fmtT(c.kartaTiyin)} · ${c.at}${c.by ? ' · ' + c.by : ''}`, fmtT(c.msTiyin) + `<small>${c.holat === 'farq' ? 'фарқ ' + (c.farqTiyin > 0 ? '+' : '') + fmtT(c.farqTiyin) : 'MoySklad'}</small>`)).join('')}</div>`
      : `<div class="card"><div class="empty">Бу кассага карта боғланмаган.</div></div>`;
  }
  if (tab === 'amallar') {
    h += `<div class="label">Охирги 20 амал</div>`;
    h += k.ops.length ? `<div class="tablewrap"><table><thead><tr><th>Сана</th><th>Тур</th><th class="num">Сумма</th><th>Изоҳ</th></tr></thead><tbody>${k.ops.map(o => `<tr><td>${dShort(o.date)}</td><td>${MT[o.mt] || ''} ${OPTYPE[o.type] || o.type}</td><td class="num" style="color:${o.in ? 'var(--good)' : 'inherit'}">${o.in ? '+' : '−'}${fmt(o.amount)}</td><td style="white-space:normal">${esc(o.comment)}</td></tr>`).join('')}</tbody></table></div>`
      : `<div class="card"><div class="empty">Амаллар йўқ.</div></div>`;
    h += `<div class="hint">Тўлиқ рўйхат: Ҳисоботлар → История.</div>`;
  }
  h += `<div class="label">Амаллар</div><div class="actions">
    <button class="btn main" id="collect">💰 Пул қабул қилиш</button>
    ${ME?.role === 'SUPERADMIN' ? '<button class="btn ghost" id="adjust">🛠 Корректировка</button>' : ''}
  </div>`;
  $main.innerHTML = h;
  bindGo();
  document.getElementById('collect').onclick = () => collectSheet(k);
  document.getElementById('adjust')?.addEventListener('click', () => adjustSheet(k));
}

/* ---- 💰 Пул қабул қилиш (SubmissionService.directCollect билан бир хил қоидалар) ---- */
function collectSheet(k) {
  const kassirs = k.kassirs.filter(u => u.role === 'KASSIR').map(u => u.name);
  const t = TODAY();
  sheet(html`<h2>💰 Пул қабул қилиш — ${esc(k.name)}</h2>
    ${k.pending.length ? `<div class="err">Бу кассанинг кўриб чиқилмаган ҳисоботи бор (#${k.pending[0].id}). Аввал уни қабул қилинг ёки рад этинг, акс ҳолда бир пул икки марта ечилади.</div>` : ''}
    <div class="seg" id="mt"><button class="on" data-mt="NAQD">💵 Нақд</button><button data-mt="TERMINAL">💳 Терминал</button></div>
    <div class="hint" id="avail">Мавжуд нақд: <b>${fmt(k.naqdMavjud)}</b> сўм${k.naqdBand ? ' (банд ' + fmt(k.naqdBand) + ')' : ''}</div>
    <div class="field"><label>Сумма (сўм) — йиғилган сумма қўйилган, керак бўлса ўзгартиринг</label><input id="sum" type="number" inputmode="numeric" value="${k.naqdMavjud > 0 ? k.naqdMavjud : ''}" placeholder="0"></div>
    <div class="field"><label>Ким топширди</label>
      ${kassirs.length ? `<select id="who">${kassirs.map(n => `<option>${esc(n)}</option>`).join('')}<option value="">Бошқа…</option></select>` : ''}
      <input id="who2" placeholder="Исм" ${kassirs.length ? 'hidden' : ''}></div>
    <div class="field"><label>Қайси сана учун</label><div class="seg" id="dt"><button class="on" data-d="${t}">Бугун</button><button data-d="${addDays(t, -1)}">Кеча</button></div><input type="date" id="dti" value="${t}" max="${t}"></div>
    <button class="btn main" id="ok" ${k.pending.length ? 'disabled' : ''}>✅ Қабул қилиш</button>`, (el, close) => {
    let mt = 'NAQD';
    const suggest = { NAQD: k.naqdMavjud, TERMINAL: (k.today || {}).prixodTerminal || 0 };
    el.querySelectorAll('#mt button').forEach(b => b.onclick = () => {
      mt = b.dataset.mt; el.querySelectorAll('#mt button').forEach(x => x.classList.toggle('on', x === b));
      el.querySelector('#avail').hidden = mt !== 'NAQD';
      el.querySelector('#sum').value = suggest[mt] > 0 ? suggest[mt] : '';   // йиғилган сумма — қўлда ўзгартириш мумкин
    });
    el.querySelectorAll('#dt button').forEach(b => b.onclick = () => { el.querySelector('#dti').value = b.dataset.d; el.querySelectorAll('#dt button').forEach(x => x.classList.toggle('on', x === b)); });
    el.querySelector('#dti').onchange = () => el.querySelectorAll('#dt button').forEach(x => x.classList.remove('on'));
    const sel = el.querySelector('#who'); if (sel) sel.onchange = () => { el.querySelector('#who2').hidden = sel.value !== ''; };
    el.querySelector('#ok').onclick = () => {
      const amount = +el.querySelector('#sum').value;
      const who = (sel && sel.value) ? sel.value : el.querySelector('#who2').value.trim();
      const date = el.querySelector('#dti').value;
      if (!(amount > 0)) { toast('Суммани киритинг'); return; }
      if (!who) { toast('Ким топширганини кўрсатинг'); return; }
      confirmSheet('Тасдиқлайсизми?', `<b>${fmt(amount)}</b> сўм (${mt === 'NAQD' ? '💵 нақд' : '💳 терминал'}) · ${esc(k.name)} → бухгалтерия · ${dShort(date)} · топширди: ${esc(who)}${mt === 'TERMINAL' ? '<br><small>Терминал пули фақат журналга ёзилади.</small>' : ''}`, async () => {
        try {
          const r = await post(`/admin/kassa/${k.id}/collect`, { mt, amount, topshirgan: who, date });
          haptic('medium'); close(); toast(`✅ Қабул қилинди #${r.opId}`); render();
        } catch (e) { toast(e.message); }
      });
    };
  });
}

/* ---- 🛠 Корректировка (фақат SuperAdmin; LedgerService.postAdjustment) ---- */
function adjustSheet(k) {
  const t = TODAY();
  sheet(html`<h2>🛠 Корректировка — ${esc(k.name)}</h2>
    <div class="hint">Ҳозир: 💵 нақд <b>${fmt(k.naqd)}</b> · 📲 click <b>${fmt(k.klik)}</b> сўм</div>
    <div class="seg" id="mt"><button class="on" data-mt="NAQD">💵 Нақд</button><button data-mt="KLIK">📲 Click</button></div>
    <div class="seg" id="mode"><button class="on" data-m="delta">± сумма</button><button data-m="target">= мақсад</button></div>
    <div class="field"><label id="suml">Сумма (сўм): + қўшиш, − айириш</label><input id="sum" type="number" inputmode="numeric" placeholder="масалан -150000"></div>
    <div class="field"><label>Сабаб</label><input id="reason" placeholder="Масалан: инвентаризация фарқи" maxlength="120"></div>
    <div class="field"><label>Сана</label><input type="date" id="dti" value="${t}" max="${t}"></div>
    <button class="btn danger" id="ok">🛠 Бажариш</button>`, (el, close) => {
    let mt = 'NAQD', mode = 'delta';
    el.querySelectorAll('#mt button').forEach(b => b.onclick = () => { mt = b.dataset.mt; el.querySelectorAll('#mt button').forEach(x => x.classList.toggle('on', x === b)); });
    el.querySelectorAll('#mode button').forEach(b => b.onclick = () => { mode = b.dataset.m; el.querySelectorAll('#mode button').forEach(x => x.classList.toggle('on', x === b)); el.querySelector('#suml').textContent = mode === 'delta' ? 'Сумма (сўм): + қўшиш, − айириш' : 'Баланс шу қийматга келтирилади (сўм)'; });
    el.querySelector('#ok').onclick = () => {
      const v = +el.querySelector('#sum').value, reason = el.querySelector('#reason').value.trim(), date = el.querySelector('#dti').value;
      if (!el.querySelector('#sum').value || (mode === 'delta' && v === 0)) { toast('Суммани киритинг'); return; }
      if (reason.length < 3) { toast('Сабабни ёзинг'); return; }
      confirmSheet('Корректировка', `${esc(k.name)} · ${mt === 'NAQD' ? '💵 нақд' : '📲 click'} · ${mode === 'delta' ? sign(v) : '= ' + fmt(v)} сўм · ${dShort(date)}<br>Сабаб: ${esc(reason)}<br><small>Бухгалтерия ва касса хабар олади.</small>`, async () => {
        try {
          const body = { mt, reason, date }; if (mode === 'delta') body.amount = v; else body.target = v;
          const r = await post(`/admin/kassa/${k.id}/adjust`, body);
          haptic('medium'); close(); toast(`✅ ${sign(r.sum)} · энди ${fmt(r.after)}`); render();
        } catch (e) { toast(e.message); }
      });
    };
  });
}

/* ============================================================
   📊 ҲИСОБОТЛАР — кунлик солиштириш · бугунги тушум · карталар · тарих · Excel
   ============================================================ */
const TODAY = () => new Date(Date.now() + 5 * 3600e3).toISOString().slice(0, 10);   // Asia/Tashkent
const addDays = (iso, n) => { const d = new Date(iso + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
const OPTYPE = { PRIXOD: 'приход', VOZVRAT: 'возврат', RASXOD: 'расход', OTKAZMA: 'ўтказма', TOPSHIRIQ: 'топшириқ', KORREKTIROVKA: 'корректировка', BOSHLANGICH: 'бошланғич' };
const MT = { NAQD: '💵', KLIK: '📲', TERMINAL: '🏧' };

/** Сана танлаш қатори: ‹ кун › + input[type=date]. onChange(iso) */
function dateBar(iso, onChange) {
  const wrap = document.createElement('div'); wrap.className = 'seg';
  wrap.innerHTML = `<button data-d="-1">‹</button><input type="date" value="${iso}" max="${TODAY()}" style="flex:3;background:none;border:0;color:var(--text);font:inherit;text-align:center;font-weight:600"><button data-d="1">›</button>`;
  wrap.querySelectorAll('button').forEach(b => b.onclick = () => onChange(addDays(iso, +b.dataset.d)));
  wrap.querySelector('input').onchange = e => onChange(e.target.value);
  return wrap;
}

async function pageHisobot(seg, q) {
  const view = seg[0] || '';
  if (view === 'kunlik') return hisobotKunlik(q);
  if (view === 'tushum') return hisobotTushum(q);
  if (view === 'karta') return hisobotKarta();
  if (view === 'tarix') return hisobotTarix(q);
  if (view === 'excel') return hisobotExcel(q);
  if (view === 'pul') return hisobotPul(q);
  setTitle('Ҳисоботлар', 'бўлимни танланг');
  $main.innerHTML = `
    <div class="label">Кун бўйича</div><div class="tiles">
      ${tile('📋', 'Кунлик солиштириш', 'MoySklad = бот? тасдиқ, чатга', '#/hisobot/kunlik')}
      ${tile('💰', 'Тушум', 'нақд · click · терминал', '#/hisobot/tushum')}
      ${tile('💳', 'Click карталар', 'MoySklad ва карта қолдиғи', '#/hisobot/karta')}
    </div>
    <div class="label">Давр бўйича</div><div class="tiles">
      ${tile('💵', 'Пул ҳаракати', 'топширилган · қабул қилинган', '#/hisobot/pul')}
      ${tile('📜', 'История', 'барча операциялар', '#/hisobot/tarix')}
    </div>
    <div class="label">Файл</div><div class="tiles">
      ${tile('📊', 'Excel', 'умумий / касса — чатга', '#/hisobot/excel')}
    </div>`;
  bindGo();
}

/* ---- 💵 Пул ҳаракати: топширилган ҳисоботлар + қабул қилинган пуллар ---- */
async function hisobotPul(q) {
  const to = q.to || TODAY(), from = q.from || addDays(to, -6), kassa = q.kassa || '0';
  const [kassas, d] = await Promise.all([api('/kassas'), api(`/admin/report/money?from=${from}&to=${to}&kassaId=${kassa}`)]);
  setTitle('Пул ҳаракати', `${dShort(from)} — ${dShort(to)}${kassa !== '0' ? ' · ' + esc(kassas.find(k => String(k.id) === kassa)?.name || '') : ''}`);
  const link = (f, t, k = kassa) => `#/hisobot/pul?from=${f}&to=${t}&kassa=${k}`;
  const pre = [['Бугун', TODAY(), TODAY()], ['7 кун', addDays(TODAY(), -6), TODAY()], ['30 кун', addDays(TODAY(), -29), TODAY()], ['Ой', TODAY().slice(0, 8) + '01', TODAY()]];
  const st = d.subTotals, ct = d.colTotals;
  const SCLS = { KUTILMOQDA: 'bad', QABUL: 'ok', QISMAN_QABUL: 'warn', RAD: '' };
  let h = `<div class="seg">${pre.map(([l, f, t]) => `<button class="${f === from && t === to ? 'on' : ''}" data-go="${link(f, t)}">${l}</button>`).join('')}</div>
    <div class="kpis">
      <div class="field"><label>Касса</label><select id="fk"><option value="0">Ҳаммаси</option>${kassas.map(k => `<option value="${k.id}" ${String(k.id) === kassa ? 'selected' : ''}>${esc(k.name)}</option>`).join('')}</select></div>
      <div class="field"><label>Давр</label><div style="display:flex;gap:4px"><input type="date" id="ff" value="${from}" max="${TODAY()}" style="flex:1"><input type="date" id="ft" value="${to}" max="${TODAY()}" style="flex:1"></div></div>
    </div>
    <div class="kpis">
      <div class="kpi"><div class="l">Қабул қилинган нақд</div><div class="v num good">${fmt(ct.naqd)}</div><div class="s">${ct.soni} амал · ${ct.viaSub} ҳисобот · ${ct.direct} бевосита</div></div>
      <div class="kpi"><div class="l">Терминал (журнал)</div><div class="v num">${fmt(ct.terminal)}</div></div>
      <div class="kpi"><div class="l">Топширилган ҳисоботлар</div><div class="v num ${st.pending ? 'bad' : ''}">${st.soni}</div><div class="s">${st.pending ? st.pending + ' та кутмоқда' : 'ҳаммаси кўрилган'}</div></div>
      <div class="kpi"><div class="l">Ҳисобот суммаси</div><div class="v num">${fmt(st.naqd)}</div><div class="s">click ${fmt(st.klik)} · қабул нақд ${fmt(st.accNaqd)}</div></div>
    </div>
    <div class="actions"><button class="btn ghost" id="xl">📤 Excel (3 варақ) чатга</button></div>`;
  h += `<div class="label">Қабул қилинган пуллар · ${ct.soni}</div>`;
  h += d.collections.length ? `<div class="tablewrap"><table><thead><tr><th>Сана</th><th>Касса</th><th>Тур</th><th class="num">Сумма</th><th>Манба</th><th>Топширди</th><th>Қабул қилди</th></tr></thead><tbody>${d.collections.map(c => `<tr><td>${dShort(c.date)}</td><td><a href="#/kassa/${c.kassaId}">${esc(c.kassa)}</a></td><td>${MT[c.mt] || c.mt}</td><td class="num"><b>${fmt(c.amount)}</b></td><td>${esc(c.source)}</td><td>${esc(c.topshirdi)}</td><td>${esc(c.qabulQildi)}${c.at ? '<br><small style="color:var(--muted)">' + c.at + '</small>' : ''}</td></tr>`).join('')}
    <tr class="total"><td>Жами</td><td></td><td></td><td class="num">${fmt(ct.naqd + ct.terminal)}</td><td colspan="3">нақд ${fmt(ct.naqd)} · терминал ${fmt(ct.terminal)}</td></tr></tbody></table></div>`
    : `<div class="card"><div class="empty">Бу даврда қабул қилинган пул йўқ.</div></div>`;
  if (d.perKassa.length > 1) h += `<div class="label">Касса кесимида</div><div class="rows">${d.perKassa.map(k => rowHtml('ok', k.kassa, `${k.soni} амал · терминал ${fmt(k.terminal)}`, fmt(k.naqd) + '<small>нақд</small>', `#/kassa/${k.kassaId}`)).join('')}</div>`;
  h += `<div class="label">Топширилган ҳисоботлар · ${st.soni}</div>`;
  h += d.submissions.length ? `<div class="rows">${d.submissions.map(s => rowHtml(SCLS[s.status] ?? '', `#${s.id} ${s.kassa}`, `${s.kassir} · ${s.days} кун · ${s.createdAt} · ${s.statusText}${s.by ? ' · ' + s.by + ' ' + s.decidedAt : ''}${s.comment ? ' · ' + s.comment : ''}`, fmt(s.naqd) + `<small>click ${fmt(s.klik)}${s.status === 'QISMAN_QABUL' ? ' · қабул ' + fmt(s.accNaqd) : ''}</small>`, s.status === 'KUTILMOQDA' ? `#/pending/${s.id}` : null)).join('')}</div>`
    : `<div class="card"><div class="empty">Бу даврда топширилган ҳисобот йўқ.</div></div>`;
  $main.innerHTML = h;
  bindGo();
  const refilter = () => go(link(document.getElementById('ff').value, document.getElementById('ft').value, document.getElementById('fk').value));
  ['fk', 'ff', 'ft'].forEach(i => document.getElementById(i).onchange = refilter);
  document.getElementById('xl').onclick = async () => { try { await post('/admin/report/money/excel', { from, to, kassaId: +kassa }); toast('📤 Чатингизга юборилмоқда…'); } catch (e) { toast(e.message); } };
}

/* ---- 📋 Кунлик солиштириш ---- */
async function hisobotKunlik(q) {
  const date = q.date || TODAY();
  const d = await api('/admin/report/daily?date=' + date);
  setTitle('Кунлик солиштириш', dUz(d.date) + (d.msKnown ? '' : ' · ⚠️ MoySklad ўқилмади'));
  const bad = d.rows.filter(r => r.farq !== 0).length;
  let h = `<div id="datebar"></div>
    <div class="kpis">
      <div class="kpi"><div class="l">MoySklad савдо</div><div class="v num">${fmt(d.jamiMs)}</div></div>
      <div class="kpi"><div class="l">Бот савдо</div><div class="v num">${fmt(d.jamiBot)}</div></div>
      <div class="kpi"><div class="l">Фарқ</div><div class="v num ${d.jamiFarq ? 'warn' : 'good'}">${sign(d.jamiFarq)}</div><div class="s">${bad ? bad + ' кассада фарқ' : 'ҳаммаси мос'}</div></div>
      <div class="kpi"><div class="l">Нақд топширилган</div><div class="v num">${fmt(d.jamiTopshirilgan)}</div></div>
    </div>
    <div class="tablewrap"><table>
      <thead><tr><th>Нуқта</th><th>Кассир</th><th class="num">MoySklad</th><th class="num">Бот</th><th class="num">Фарқ</th><th class="num">Топширилган</th><th class="num">P2P қолдиқ</th></tr></thead>
      <tbody>${d.rows.map(r => `<tr>
        <td>${r.farq === 0 ? '✅' : '⚠️'} <a href="#/kassa/${r.kassaId}">${esc(r.nuqta)}</a></td><td>${esc(r.kassir)}</td>
        <td class="num">${fmt(r.msSavdo)}</td><td class="num">${fmt(r.botSavdo)}</td>
        <td class="num" style="color:${r.farq ? 'var(--warn)' : 'inherit'}">${r.farq ? sign(r.farq) : '0'}</td>
        <td class="num">${fmt(r.naqdTopshirilgan)}</td><td class="num">${r.p2pTiyin == null ? '—' : fmtT(r.p2pTiyin) + (r.p2pKnown ? '' : ' *')}</td></tr>`).join('')}
      <tr class="total"><td>Жами</td><td></td><td class="num">${fmt(d.jamiMs)}</td><td class="num">${fmt(d.jamiBot)}</td><td class="num">${sign(d.jamiFarq)}</td><td class="num">${fmt(d.jamiTopshirilgan)}</td><td></td></tr></tbody>
    </table></div>
    <div class="hint">Фарқ = MoySklad савдоси − бот савдоси. P2P «*» — карта қолдиғи киритилмаган, бот қиймати. Автоматик юбориш: ${d.time}.</div>
    <div class="card">${d.confirm
      ? `<div>✔️ Тасдиқлади: <b>${esc(d.confirm.userName || '?')}</b>, ${d.confirm.at}</div>`
      : `<div>⏳ Молия менежери тасдиғи кутилмоқда</div>`}</div>
    <div class="actions">
      ${d.confirm ? '' : '<button class="btn main" id="ok">✅ Тасдиқлаш (молия менежери)</button>'}
      <button class="btn ghost" id="send">📤 Чатга юбориш (расм + Excel)</button>
    </div>`;
  $main.innerHTML = h;
  document.getElementById('datebar').replaceWith(dateBar(date, iso => go('#/hisobot/kunlik?date=' + iso)));
  document.getElementById('ok')?.addEventListener('click', () => confirmSheet('Кунлик ҳисоботни тасдиқлаш',
    `${dUz(d.date)} учун MoySklad ва бот солиштируви тасдиқланади. Бу қарор ботдаги ҳисоботда ҳам кўринади.`,
    async () => { try { const r = await api('/admin/report/daily/confirm', { method: 'POST', body: JSON.stringify({ date }) }); haptic('medium'); toast(r.fresh ? '✅ Тасдиқланди' : 'Бу кун аллақачон тасдиқланган'); render(); } catch (e) { toast(e.message); } }));
  document.getElementById('send').onclick = async () => {
    try { await api('/admin/report/daily/send', { method: 'POST', body: JSON.stringify({ date }) }); toast('📤 Чатингизга юборилмоқда…'); } catch (e) { toast(e.message); }
  };
}

/* ---- 💰 Бугунги тушум ---- */
async function hisobotTushum(q) {
  const date = q.date || TODAY();
  const d = await api('/admin/report/tushum?date=' + date);
  setTitle('Тушум', dUz(d.date));
  $main.innerHTML = `<div id="datebar"></div>
    <div class="kpis">
      <div class="kpi"><div class="l">Жами</div><div class="v num">${fmt(d.jami)}</div></div>
      <div class="kpi"><div class="l">💵 Нақд</div><div class="v num">${fmt(d.naqd)}</div></div>
      <div class="kpi"><div class="l">📲 Click</div><div class="v num">${fmt(d.klik)}</div></div>
      <div class="kpi"><div class="l">🏧 Терминал</div><div class="v num">${fmt(d.terminal)}</div></div>
    </div>
    <div class="tablewrap"><table>
      <thead><tr><th>Касса</th><th class="num">Жами</th><th class="num">💵</th><th class="num">📲</th><th class="num">🏧</th><th class="num">Возврат</th><th class="num">Расход</th></tr></thead>
      <tbody>${d.rows.map(r => `<tr><td><a href="#/kassa/${r.kassaId}">${esc(r.name)}</a></td><td class="num"><b>${fmt(r.jami)}</b></td><td class="num">${fmt(r.naqd)}</td><td class="num">${fmt(r.klik)}</td><td class="num">${fmt(r.terminal)}</td><td class="num">${fmt(r.vozvrat)}</td><td class="num">${fmt(r.rasxod)}</td></tr>`).join('')}
      <tr class="total"><td>Жами</td><td class="num">${fmt(d.jami)}</td><td class="num">${fmt(d.naqd)}</td><td class="num">${fmt(d.klik)}</td><td class="num">${fmt(d.terminal)}</td><td></td><td></td></tr></tbody>
    </table></div>`;
  document.getElementById('datebar').replaceWith(dateBar(date, iso => go('#/hisobot/tushum?date=' + iso)));
}

/* ---- 💳 Карталар ---- */
async function hisobotKarta() {
  const d = await api('/admin/cards');
  setTitle('Click карталар', `ҳолат ${d.asOf}${d.msKnown ? '' : ' · ⚠️ MoySklad ўқилмади'}`);
  let h = `<div class="kpis">
    <div class="kpi"><div class="l">Тенг</div><div class="v num good">${d.xulosa.teng}</div></div>
    <div class="kpi"><div class="l">Фарқ</div><div class="v num ${d.xulosa.farq ? 'warn' : ''}">${d.xulosa.farq}</div></div>
    <div class="kpi"><div class="l">Киритилмаган</div><div class="v num ${d.xulosa.kiritilmagan ? 'bad' : ''}">${d.xulosa.kiritilmagan}</div></div>
    <div class="kpi"><div class="l">Жами карта / MS</div><div class="v num">${fmtT(d.xulosa.kartaTiyin)}</div><div class="s">${fmtT(d.xulosa.msTiyin)}</div></div>
  </div><div class="rows">`;
  for (const c of d.kartalar)
    h += rowHtml(holatCls(c.holat), c.name + (c.kassa ? ' · ' + c.kassa : ''),
      c.holat === 'kiritilmagan' ? 'қолдиқ киритилмаган' + (c.masul ? ' · масъул ' + c.masul : '') : `карта ${fmtT(c.kartaTiyin)} · ${c.at}${c.by ? ' · ' + c.by : ''}`,
      fmtT(c.msTiyin) + `<small>${c.holat === 'farq' ? 'фарқ ' + (c.farqTiyin > 0 ? '+' : '') + fmtT(c.farqTiyin) : HOLAT[c.holat]}</small>`);
  $main.innerHTML = h + '</div>';
}

/* ---- 📜 История ---- */
async function hisobotTarix(q) {
  const to = q.to || TODAY(), from = q.from || addDays(to, -6), kassa = q.kassa || '0', type = q.type || '';
  const [kassas, ops] = await Promise.all([api('/kassas'), api(`/operations?from=${from}&to=${to}&kassaId=${kassa}&type=${type}`)]);
  setTitle('История', `${dShort(from)} — ${dShort(to)} · ${ops.length} та${ops.length >= 500 ? ' (чекланган)' : ''}`);
  const link = (f, t) => `#/hisobot/tarix?from=${f}&to=${t}&kassa=${kassa}&type=${type}`;
  const pre = [['Бугун', TODAY(), TODAY()], ['Кеча', addDays(TODAY(), -1), addDays(TODAY(), -1)], ['7 кун', addDays(TODAY(), -6), TODAY()], ['30 кун', addDays(TODAY(), -29), TODAY()]];
  let h = `<div class="seg">${pre.map(([l, f, t]) => `<button class="${f === from && t === to ? 'on' : ''}" data-go="${link(f, t)}">${l}</button>`).join('')}</div>
    <div class="kpis">
      <div class="field"><label>Касса</label><select id="fk"><option value="0">Ҳаммаси</option>${kassas.map(k => `<option value="${k.id}" ${String(k.id) === kassa ? 'selected' : ''}>${esc(k.name)}</option>`).join('')}</select></div>
      <div class="field"><label>Тур</label><select id="ft"><option value="">Ҳаммаси</option>${Object.entries(OPTYPE).map(([k, v]) => `<option value="${k}" ${k === type ? 'selected' : ''}>${v}</option>`).join('')}</select></div>
    </div>`;
  if (!ops.length) h += `<div class="card"><div class="empty">Бу давр учун операция йўқ.</div></div>`;
  else {
    h += `<div class="tablewrap"><table><thead><tr><th>Сана</th><th>Тур</th><th class="num">Сумма</th><th>Қаердан</th><th>Қаерга</th><th>Ҳолат</th><th>Изоҳ</th></tr></thead><tbody>`;
    for (const o of ops) h += `<tr><td>${dShort(o.date)}</td><td>${MT[o.mt] || ''} ${OPTYPE[o.type] || o.type}</td><td class="num">${fmt(o.amount)}</td><td>${esc(o.from)}</td><td>${esc(o.to)}</td><td>${o.status === 'TASDIQLANGAN' ? '✅' : o.status === 'KUTILMOQDA' ? '⏳' : o.status === 'RAD_ETILGAN' ? '❌' : esc(o.status)}</td><td>${esc(o.category ? o.category + ' · ' : '')}${esc(o.comment)}</td></tr>`;
    h += `</tbody></table></div>`;
  }
  $main.innerHTML = h;
  bindGo();
  const refilter = () => go(`#/hisobot/tarix?from=${from}&to=${to}&kassa=${document.getElementById('fk').value}&type=${document.getElementById('ft').value}`);
  document.getElementById('fk').onchange = refilter;
  document.getElementById('ft').onchange = refilter;
}

/* ---- 📊 Excel ---- */
async function hisobotExcel(q) {
  const kassas = await api('/kassas');
  setTitle('Excel', 'файл чатингизга юборилади');
  const t = TODAY();
  $main.innerHTML = `<div class="card">
      <div class="field"><label>Бошланиш</label><input type="date" id="f" value="${addDays(t, -29)}" max="${t}"></div>
      <div class="field"><label>Тугаш</label><input type="date" id="t" value="${t}" max="${t}"></div>
      <div class="seg">${[['Бугун', 0], ['7 кун', 6], ['30 кун', 29], ['90 кун', 89]].map(([l, n]) => `<button data-n="${n}">${l}</button>`).join('')}</div>
      <div class="field"><label>Касса</label><select id="k"><option value="0">Умумий (барча кассалар)</option>${kassas.map(k => `<option value="${k.id}">${esc(k.name)}</option>`).join('')}</select></div>
      <button class="btn main" id="send">📊 Excel тайёрлаб чатга юбориш</button>
      <div class="hint">Варақлар: Умумий · Транзакциялар · MoySklad ҳужжатлари. MoySklad сўралади, 10–30 сония.</div>
    </div>`;
  $main.querySelectorAll('.seg button').forEach(b => b.onclick = () => { document.getElementById('f').value = addDays(t, -b.dataset.n); document.getElementById('t').value = t; });
  document.getElementById('send').onclick = async () => {
    const btn = document.getElementById('send'); btn.disabled = true;
    try {
      const r = await api('/admin/report/excel', { method: 'POST', body: JSON.stringify({ from: document.getElementById('f').value, to: document.getElementById('t').value, kassaId: +document.getElementById('k').value }) });
      haptic('medium'); toast('📤 Тайёрланмоқда: ' + r.label);
    } catch (e) { toast(e.message); }
    btn.disabled = false;
  };
}

/* ============================================================
   ⚙️ СОЗЛАМАЛАР — меню тартиби · тугма номлари · ҳуқуқлар · шаблонлар
   (фақат SuperAdmin; бот admin панели билан бир манба — settings жадвали)
   ============================================================ */
const put = (path, body) => api(path, { method: 'PUT', body: JSON.stringify(body) });
const post = (path, body) => api(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });

async function pageSozlama(seg, q) {
  if (ME?.role !== 'SUPERADMIN') {
    setTitle('Созламалар', 'фақат SuperAdmin');
    $main.innerHTML = `<div class="card"><div class="empty">Созламаларни фақат SuperAdmin ўзгартиради.</div></div>`;
    return;
  }
  const view = seg[0] || '';
  if (view === 'menyu') return sozMenyu(seg[1]);
  if (view === 'sxema') return sozSxema();
  if (view === 'nomlar') return sozNomlar();
  if (view === 'huquq') return sozHuquq(seg[1], seg[2]);
  if (view === 'shablon') return sozShablon(seg[1], q);
  if (view === 'kassa') return orgKassa();
  if (view === 'xodim') return orgXodim();
  if (view === 'karta') return orgKarta();
  if (view === 'guruh') return orgGuruh();
  if (view === 'moliya') return moliya(seg[1]);
  if (view === 'moysklad') return moysklad(seg[1]);
  if (view === 'audit') return auditPage(q);
  setTitle('Созламалар', 'SuperAdmin');
  $main.innerHTML = `
    <div class="label">🏢 Ташкилот</div><div class="tiles">
      ${tile('🏪', 'Кассалар', 'қўшиш · отдел · ўчириш', '#/sozlama/kassa')}
      ${tile('👥', 'Ходимлар', 'қўшиш · рол · ўчириш', '#/sozlama/xodim')}
      ${tile('💳', 'Карта масъуллари', 'ҳар карта учун ходим', '#/sozlama/karta')}
      ${tile('📣', 'Гуруҳлар / Каналлар', 'Click ҳисоботи, жадвал', '#/sozlama/guruh')}
    </div>
    <div class="label">💼 Молия</div><div class="tiles">
      ${tile('💼', 'Бошланғич қолдиқ', 'касса / Основной / карта', '#/sozlama/moliya/init')}
      ${tile('🛠', 'Корректировка', '± сумма ёки = мақсад', '#/sozlama/moliya/adjust')}
      ${tile('📅', 'Ledger санаси', 'синхрон бошланиши', '#/sozlama/moliya/ledger')}
      ${tile('♻️', 'Нол бошлаш', 'олдинги кунларни ёпиш', '#/sozlama/moliya/zero', 'warn')}
    </div>
    <div class="label">🔗 MoySklad</div><div class="tiles">
      ${tile('🔑', 'API калити', 'ҳолат · янги калит', '#/sozlama/moysklad/token')}
      ${tile('🔄', 'Номлар', 'янгилаш · қўлда · 🔒', '#/sozlama/moysklad/names')}
      ${tile('🩺', 'Диагностика', 'минус · такрор рақам', '#/sozlama/moysklad/diag')}
      ${tile('📥', 'Қайта юклаш', 'ҳаммасини қайта тортиш', '#/sozlama/moysklad/reload', 'warn')}
    </div>
    <div class="label">🎛 Интерфейс</div><div class="tiles">
      ${tile('🧩', 'Бот сxемаси', 'тугмаларни судраб кўчириш, яшириш', '#/sozlama/sxema', 'ok')}
      ${tile('🏷', 'Тугма номлари', 'ном · яшириш', '#/sozlama/nomlar')}
      ${tile('👁', 'Ҳуқуқлар', 'ходим · отдел кесимида', '#/sozlama/huquq')}
      ${tile('🔔', 'Билдиришномалар', 'шаблон · жадвал · намуна', '#/sozlama/shablon')}
      ${tile('📋', 'Аудит', 'ким нима қилди · Excel', '#/sozlama/audit')}
    </div>`;
  bindGo();
}

const del = path => api(path, { method: 'DELETE' });
const ROLE = { KASSIR: 'кассир', BUXGALTER: 'бухгалтер', SUPERADMIN: 'SuperAdmin' };
const kassaSelect = (id, kassas, cur) => `<select id="${id}">${kassas.map(k => `<option value="${k.id}" ${String(k.id) === String(cur) ? 'selected' : ''}>${esc(k.name)}</option>`).join('')}</select>`;

/* ================= 🏢 ТАШКИЛОТ ================= */
async function orgKassa() {
  const d = await api('/admin/org/kassa');
  setTitle('Кассалар', `${d.kassalar.length} та${d.msOk ? '' : ' · ⚠️ MoySklad отделлари ўқилмади'}`);
  $main.innerHTML = `<div class="rows">${d.kassalar.map(k => `<div class="row ${k.groupId ? 'ok' : 'warn'}">
      <div class="t"><b>${esc(k.name)}${k.nameLocked ? ' 🔒' : ''}${k.cashless ? ' · нақдсиз' : ''}</b><span>${k.groupName ? 'отдел: ' + esc(k.groupName) : '⚠️ отдел боғланмаган — ҳужжатлар Бухгалтерияга'}${k.label ? ' · нуқта: ' + esc(k.label) : ''} · ${k.xodimlar} ходим</span></div>
      <button class="btn sm ghost" data-edit="${k.id}">✏️</button><button class="btn sm ghost" data-otdel="${k.id}">🗂</button><button class="btn sm ghost" data-del="${k.id}" ${k.canDeactivate ? '' : 'disabled'}>🚫</button></div>`).join('')}</div>
    <div class="actions"><button class="btn main" id="add">➕ Касса қўшиш</button></div>
    <div class="hint">🗂 — MoySklad отделини боғлаш (битта отдел фақат битта кассада). 🚫 фақат баланси ва топширилмаган қолдиғи 0 бўлган кассада очиқ.</div>`;
  const groupOpts = (cur) => `<option value="">— боғланмаган —</option>` + d.groups.map(g => `<option value="${esc(g.id)}" ${g.id === cur ? 'selected' : ''}>${esc(g.name)}${g.holders.length ? ' (' + g.holders.join(', ') + ')' : ''}</option>`).join('');
  document.getElementById('add').onclick = () => sheet(html`<h2>➕ Янги касса</h2>
      <div class="field"><label>Ном (2–40)</label><input id="n" maxlength="40"></div>
      <div class="field"><label>MoySklad отдели</label><select id="g">${groupOpts('')}</select></div>
      <div class="field"><label>Савдо нуқтаси ID (ихтиёрий)</label><input id="s" placeholder="MoySklad store id"></div>
      <button class="btn main" id="ok">Қўшиш</button>`, (el, close) => {
      el.querySelector('#ok').onclick = async () => { try { await post('/admin/org/kassa', { name: el.querySelector('#n').value, groupId: el.querySelector('#g').value, storeId: el.querySelector('#s').value }); close(); toast('✅ Қўшилди'); render(); } catch (e) { toast(e.message); } };
    });
  $main.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => {
    const k = d.kassalar.find(x => String(x.id) === b.dataset.edit);
    sheet(html`<h2>✏️ ${esc(k.name)}</h2>
      <div class="field"><label>Нуқта номи (кунлик ҳисоботда; бўш — касса номи)</label><input id="l" value="${esc(k.label)}" maxlength="40"></div>
      <div class="field"><label><input type="checkbox" id="c" ${k.cashless ? 'checked' : ''}> Нақдсиз касса (кунлик солиштиришда қатнашмайди)</label></div>
      <button class="btn main" id="ok">Сақлаш</button>`, (el, close) => {
      el.querySelector('#ok').onclick = async () => { try { await put('/admin/org/kassa/' + k.id, { label: el.querySelector('#l').value, cashless: el.querySelector('#c').checked }); close(); toast('✅'); render(); } catch (e) { toast(e.message); } };
    });
  });
  $main.querySelectorAll('[data-otdel]').forEach(b => b.onclick = () => {
    const k = d.kassalar.find(x => String(x.id) === b.dataset.otdel);
    sheet(html`<h2>🗂 Отдел — ${esc(k.name)}</h2><div class="field"><label>MoySklad отдели</label><select id="g">${groupOpts(k.groupId)}</select></div>
      <div class="hint">Бўш танланса отдел олиб ташланади: кассага MoySklad'дан ҳеч нарса тушмайди.</div><button class="btn main" id="ok">Сақлаш</button>`, (el, close) => {
      el.querySelector('#ok').onclick = async () => {
        const groupId = el.querySelector('#g').value;
        try {
          const r = await put(`/admin/org/kassa/${k.id}/otdel`, { groupId, move: false });
          if (r.needMove) { confirmSheet('Кўчирилсинми?', `«${esc(r.groupName)}» отдели ${esc(r.holders.join(', '))} кассасига боғланган. ${esc(k.name)} га кўчирилсинми? (аввалгисидан олинади)`, async () => { try { await put(`/admin/org/kassa/${k.id}/otdel`, { groupId, move: true }); close(); toast('✅ Кўчирилди'); render(); } catch (e) { toast(e.message); } }); return; }
          close(); toast(r.warning ? '➖ ' + r.warning : '✅ Боғланди'); render();
        } catch (e) { toast(e.message); }
      };
    });
  });
  $main.querySelectorAll('[data-del]').forEach(b => b.onclick = () => {
    const k = d.kassalar.find(x => String(x.id) === b.dataset.del);
    confirmSheet('🚫 Ўчириш', `«${esc(k.name)}» фаолсизланади, тарих сақланади.`, async () => { try { await del('/admin/org/kassa/' + k.id); toast('🚫 Ўчирилди'); render(); } catch (e) { toast(e.message); } });
  });
}

async function orgXodim() {
  const [d, kassas] = await Promise.all([api('/admin/org/users'), api('/kassas')]);
  setTitle('Ходимлар', `${d.users.length} фаол · ${d.guests.length} кутаётган · ${d.employees.length} MoySklad`);
  const roleSel = (id, cur) => `<select id="${id}"><option value="KASSIR" ${cur === 'KASSIR' ? 'selected' : ''}>👤 Кассир</option><option value="BUXGALTER" ${cur === 'BUXGALTER' ? 'selected' : ''}>🧮 Бухгалтер</option><option value="SUPERADMIN" ${cur === 'SUPERADMIN' ? 'selected' : ''}>👑 SuperAdmin</option></select>`;
  $main.innerHTML = `<div class="rows">${d.users.map(u => `<div class="row ${u.tgId ? 'ok' : 'warn'}">
      <div class="t"><b>${esc(u.name)}${u.creator ? ' 👑' : ''}</b><span>${ROLE[u.role]}${u.kassa ? ' · ' + esc(u.kassa) : ''}${u.tgId ? '' : ' · Telegram уланмаган'}${u.phone ? ' · ' + esc(u.phone) : ''}</span></div>
      <button class="btn sm ghost" data-role="${u.id}">🔄</button><button class="btn sm ghost" data-del="${u.id}" ${u.creator ? 'disabled' : ''}>🚫</button></div>`).join('')}</div>
    <div class="actions"><button class="btn main" id="add">➕ Ходим қўшиш</button></div>
    ${d.guests.length ? `<div class="label">Ботга ёзганлар (кутмоқда)</div><div class="rows">${d.guests.map(g => `<div class="row"><div class="t"><b>${esc(g.name || g.tgId)}</b><span>${g.phone ? esc(g.phone) : g.username ? '@' + esc(g.username) : 'tg ' + g.tgId}</span></div><button class="btn sm main" data-guest="${g.tgId}">➕</button></div>`).join('')}</div>` : ''}
    ${d.employees.length ? `<div class="label">MoySklad ходимлари (тизимда йўқ)</div><div class="rows">${d.employees.map((e, i) => `<div class="row"><div class="t"><b>${esc(e.name)}</b><span>${esc(e.phone || 'телефони йўқ')} · Telegram'сиз яратилади, телефонини юборса уланади</span></div><button class="btn sm main" data-emp="${i}">➕</button></div>`).join('')}</div>` : ''}`;
  const addSheet = (pre = {}) => sheet(html`<h2>➕ Янги ходим</h2>
      <div class="field"><label>Исм-фамилия</label><input id="n" value="${esc(pre.name || '')}"></div>
      <div class="field"><label>Telegram ID (бўш бўлса телефон орқали уланади)</label><input id="t" type="number" value="${pre.tgId || ''}"></div>
      <div class="field"><label>Телефон</label><input id="p" value="${esc(pre.phone || '')}" placeholder="+998…"></div>
      <div class="field"><label>Рол</label>${roleSel('r', 'KASSIR')}</div>
      <div class="field" id="kw"><label>Касса (кассир учун)</label>${kassaSelect('k', kassas, '')}</div>
      <button class="btn main" id="ok">Қўшиш</button>`, (el, close) => {
      el.querySelector('#r').onchange = e => { el.querySelector('#kw').hidden = e.target.value !== 'KASSIR'; };
      el.querySelector('#ok').onclick = async () => { try { const r = await post('/admin/org/users', { name: el.querySelector('#n').value, tgId: +el.querySelector('#t').value || 0, phone: el.querySelector('#p').value, role: el.querySelector('#r').value, kassaId: +el.querySelector('#k').value }); close(); toast(r.tgLinked ? '✅ Қўшилди, Telegram уланган' : '✅ Қўшилди (Telegram кейин уланади)'); render(); } catch (e) { toast(e.message); } };
    });
  document.getElementById('add').onclick = () => addSheet();
  $main.querySelectorAll('[data-guest]').forEach(b => b.onclick = () => { const g = d.guests.find(x => String(x.tgId) === b.dataset.guest); addSheet({ name: g.name, tgId: g.tgId, phone: g.phone }); });
  $main.querySelectorAll('[data-emp]').forEach(b => b.onclick = () => addSheet(d.employees[+b.dataset.emp]));
  $main.querySelectorAll('[data-role]').forEach(b => b.onclick = () => {
    const u = d.users.find(x => String(x.id) === b.dataset.role);
    sheet(html`<h2>🔄 ${esc(u.name)}</h2><div class="field"><label>Янги рол</label>${roleSel('r', u.role)}</div><div class="field" id="kw" ${u.role === 'KASSIR' ? '' : 'hidden'}><label>Касса</label>${kassaSelect('k', kassas, u.kassaId)}</div><button class="btn main" id="ok">Сақлаш</button>`, (el, close) => {
      el.querySelector('#r').onchange = e => { el.querySelector('#kw').hidden = e.target.value !== 'KASSIR'; };
      el.querySelector('#ok').onclick = async () => { try { await put(`/admin/org/users/${u.id}/role`, { role: el.querySelector('#r').value, kassaId: +el.querySelector('#k').value }); close(); toast('✅ Рол ўзгартирилди'); render(); } catch (e) { toast(e.message); } };
    });
  });
  $main.querySelectorAll('[data-del]').forEach(b => b.onclick = () => { const u = d.users.find(x => String(x.id) === b.dataset.del); confirmSheet('🚫 Фаолсизлантириш', `${esc(u.name)} ботдан фойдалана олмайди.`, async () => { try { await del('/admin/org/users/' + u.id); toast('🚫'); render(); } catch (e) { toast(e.message); } }); });
}

async function orgKarta() {
  const d = await api('/admin/org/cards');
  setTitle('Карта масъуллари', d.cards.length + ' та карта');
  $main.innerHTML = `<div class="rows">${d.cards.map(c => `<div class="row ${c.masul ? 'ok' : 'warn'}"><div class="t"><b>${esc(c.name)}</b><span>${c.kassa ? esc(c.kassa) + ' · ' : ''}${c.masul ? 'масъул: ' + esc(c.masul) : 'масъул йўқ'}</span></div>
      <select data-card="${c.id}"><option value="0">— йўқ —</option>${d.users.map(u => `<option value="${u.id}" ${u.name === c.masul ? 'selected' : ''}>${esc(u.name)}</option>`).join('')}</select></div>`).join('')}</div>
    <div class="hint">Масъул ходим Click ҳисоботида карта ёнида @ билан кўрсатилади ва «Карта қолдиқларини юборинг» чақирувини олади.</div>`;
  $main.querySelectorAll('[data-card]').forEach(s => s.onchange = async () => { try { await put(`/admin/org/cards/${s.dataset.card}/owner`, { userId: +s.value }); toast('✅'); render(); } catch (e) { toast(e.message); } });
}

async function orgGuruh() {
  const d = await api('/admin/org/click');
  setTitle('Гуруҳлар / Каналлар', `${d.chats.length} та чат`);
  $main.innerHTML = `<div class="rows">${d.chats.map(c => `<div class="row ${c.ok ? 'ok' : 'bad'}"><div class="t"><b>${c.channel ? '📢' : '👥'} ${esc(c.name)}</b><span>${c.id}${c.ok ? '' : ' · бот бу чатда топилмади'}</span></div><button class="btn sm ghost" data-del="${c.id}">🗑</button></div>`).join('') || '<div class="card"><div class="empty">Чат уланмаган.</div></div>'}</div>
    <div class="actions"><button class="btn main" id="add">➕ Гуруҳ/канал қўшиш</button>${d.chats.length ? '<button class="btn ghost" id="test">🧪 Ҳозир тест юбориш</button>' : ''}</div>
    <div class="label">⏰ Click ҳисоботи жадвали</div>
    <div class="card"><div class="kpis">
      <div class="field"><label>Ҳар неча соатда</label><select id="every">${[1, 2, 3, 4, 6, 12, 24].map(h => `<option ${h === d.every ? 'selected' : ''}>${h}</option>`).join('')}</select></div>
      <div class="field"><label>Силжиш (мин)</label><select id="off">${[-20, -15, -10, -5, 0, 5, 10, 15, 20].map(m => `<option value="${m}" ${m === d.offset ? 'selected' : ''}>${m > 0 ? '+' + m : m}</option>`).join('')}</select></div>
      <div class="field"><label>Дан (соат)</label><input id="from" type="number" min="0" max="23" value="${d.from}"></div>
      <div class="field"><label>Гача (соат)</label><input id="to" type="number" min="0" max="23" value="${d.to}"></div>
    </div>
    <div class="field"><label>✍️ Ост матн (300 гача; {adminlar} {xodimlar} ишлайди)</label><textarea id="footer" rows="2">${esc(d.footer)}</textarea></div>
    <div class="field"><label>📋 Кунлик солиштириш юбориш вақти</label><input id="daily" value="${esc(d.dailyTime)}" placeholder="22:00"></div>
    <button class="btn main" id="save">💾 Сақлаш</button></div>
    <div class="hint">Бот: @${esc(d.botUsername)}. Чатга аввал ботни қўшинг, кейин ID ни киритинг (масалан -1001234567890).</div>`;
  document.getElementById('add').onclick = () => sheet(`<h2>➕ Чат</h2><div class="field"><label>Chat ID</label><input id="c" type="number" placeholder="-100…"></div><button class="btn main" id="ok">Қўшиш</button>`, (el, close) => {
    el.querySelector('#ok').onclick = async () => { try { const r = await post('/admin/org/click/chats', { chatId: +el.querySelector('#c').value }); close(); toast('✅ ' + r.name); render(); } catch (e) { toast(e.message); } };
  });
  document.getElementById('test')?.addEventListener('click', async () => { try { await post('/admin/org/click/test'); toast('🧪 Юборилмоқда…'); } catch (e) { toast(e.message); } });
  $main.querySelectorAll('[data-del]').forEach(b => b.onclick = () => confirmSheet('🗑 Олиб ташлаш', 'Бу чатга ҳисобот бормайди.', async () => { try { await del('/admin/org/click/chats/' + b.dataset.del); render(); } catch (e) { toast(e.message); } }));
  document.getElementById('save').onclick = async () => {
    const v = i => document.getElementById(i).value;
    try { await put('/admin/org/click/schedule', { every: +v('every'), offset: +v('off'), from: +v('from'), to: +v('to'), footer: v('footer'), dailyTime: v('daily') }); toast('✅ Сақланди'); } catch (e) { toast(e.message); }
  };
}

/* ================= 💼 МОЛИЯ ================= */
async function moliya(view) {
  if (view === 'ledger') {
    const d = await api('/admin/moliya/ledger');
    setTitle('Ledger санаси', d.effective ? dUz(d.effective) + ' · манба: ' + (d.source === 'bot' ? 'созлама' : '.env') : 'белгиланмаган');
    $main.innerHTML = `<div class="card"><p class="hint">Бу санадан ОЛДИНГИ, базада йўқ MoySklad ҳужжатлари синхронда қайта ўқилмайди (бошланғич қолдиқлар шу санага калибрланган). Одатда бошланғич қолдиқ қайта киритилгандагина ўзгартирилади.</p>
      <div class="field"><label>Сана</label><input type="date" id="d" value="${d.effective}" max="${TODAY()}"></div>
      <button class="btn main" id="ok">💾 Сақлаш</button>${d.override && d.env ? `<button class="btn ghost" id="env">♻️ .env қийматига қайтариш (${d.env})</button>` : ''}</div>`;
    document.getElementById('ok').onclick = async () => { try { await put('/admin/moliya/ledger', { date: document.getElementById('d').value }); toast('✅'); render(); } catch (e) { toast(e.message); } };
    document.getElementById('env')?.addEventListener('click', async () => { try { await put('/admin/moliya/ledger', { date: '' }); toast('♻️'); render(); } catch (e) { toast(e.message); } });
    return;
  }
  if (view === 'zero') {
    const kassas = await api('/kassas');
    setTitle('Нол бошлаш', 'олдинги кунларни ёпиш');
    $main.innerHTML = `<div class="card"><p class="hint">Бугундан ОЛДИНГИ барча топширилмаган кунлар «қабул қилинган» деб ёпилади, қолдиғи касса балансидан чиқарилади — касса 0 дан бошлайди. Бугунги тушум сақланади, тарих журналда қолади. Ортга қайтарилмайди.</p>
      <div class="field"><label>Кимни</label><select id="a"><option value="all">🏢 Барча кассалар</option>${kassas.map(k => `<option value="${k.id}">🏪 ${esc(k.name)}</option>`).join('')}</select></div>
      <button class="btn ghost" id="prev">👁 Нима ёпилади?</button><div id="pv"></div></div>`;
    document.getElementById('prev').onclick = async () => {
      const arg = document.getElementById('a').value;
      try {
        const p = await api('/admin/moliya/zero?arg=' + arg);
        document.getElementById('pv').innerHTML = `<div class="tablewrap"><table><thead><tr><th>Касса</th><th class="num">Кун</th><th class="num">Нақд</th><th class="num">Click</th></tr></thead><tbody>${p.rows.map(r => `<tr><td>${esc(r.name)}</td><td class="num">${r.days}</td><td class="num">${fmt(r.naqd)}</td><td class="num">${fmt(r.klik)}</td></tr>`).join('')}<tr class="total"><td>Жами</td><td class="num">${p.days}</td><td class="num">${fmt(p.naqd)}</td><td class="num">${fmt(p.klik)}</td></tr></tbody></table></div>
          <button class="btn danger" id="go" ${p.days ? '' : 'disabled'}>♻️ Ҳа, нол қилинсин</button>`;
        document.getElementById('go').onclick = () => confirmSheet('♻️ Нол бошлаш', `${p.days} кун ёпилади, нақд ${fmt(p.naqd)} · click ${fmt(p.klik)} сўм балансдан чиқарилади. Ортга қайтарилмайди.`, async () => { try { await post('/admin/moliya/zero', { arg }); toast('♻️ Бажарилди'); render(); } catch (e) { toast(e.message); } });
      } catch (e) { toast(e.message); }
    };
    return;
  }
  const owners = await api('/admin/moliya/owners');
  const ownerSel = `<select id="o">${owners.map(o => `<option value="${o.code}">${esc(o.name)} — ${o.naqd == null ? '' : 'нақд ' + fmt(o.naqd) + ' · '}click ${fmt(o.klik)}</option>`).join('')}</select>`;
  if (view === 'init') {
    setTitle('Бошланғич қолдиқ', 'касса / Основной / карта');
    $main.innerHTML = `<div class="card"><div class="field"><label>Эга</label>${ownerSel}</div>
      <div class="kpis"><div class="field"><label>💵 Нақд (сўм)</label><input id="n" type="number" value="0"></div><div class="field"><label>📲 Click (сўм)</label><input id="k" type="number" value="0"></div></div>
      <div class="field"><label>Сана</label><input type="date" id="d" value="${TODAY()}" max="${TODAY()}"></div>
      <button class="btn main" id="ok">💾 Киритиш</button><div class="hint">Балансга қўшилади (BOSHLANGICH операцияси). Click ҳисобида фақат click.</div></div>`;
    document.getElementById('ok').onclick = () => { const v = i => document.getElementById(i).value; confirmSheet('Бошланғич қолдиқ', `нақд ${fmt(v('n'))} · click ${fmt(v('k'))} · ${dShort(v('d'))}`, async () => { try { const r = await post('/admin/moliya/init', { owner: v('o'), naqd: +v('n'), klik: +v('k'), date: v('d') }); toast('✅ ' + r.owner); go('#/sozlama'); } catch (e) { toast(e.message); } }); };
    return;
  }
  setTitle('Корректировка', 'исталган эга');
  $main.innerHTML = `<div class="card"><div class="field"><label>Эга</label>${ownerSel}</div>
    <div class="seg" id="mt"><button class="on" data-mt="NAQD">💵 Нақд</button><button data-mt="KLIK">📲 Click</button></div>
    <div class="seg" id="mode"><button class="on" data-m="delta">± сумма</button><button data-m="target">= мақсад</button></div>
    <div class="field"><label>Сумма (сўм)</label><input id="sum" type="number" placeholder="масалан -150000"></div>
    <div class="field"><label>Сабаб</label><input id="reason" maxlength="120"></div>
    <div class="field"><label>Сана</label><input type="date" id="d" value="${TODAY()}" max="${TODAY()}"></div>
    <button class="btn danger" id="ok">🛠 Бажариш</button></div>`;
  let mt = 'NAQD', mode = 'delta';
  $main.querySelectorAll('#mt button').forEach(b => b.onclick = () => { mt = b.dataset.mt; $main.querySelectorAll('#mt button').forEach(x => x.classList.toggle('on', x === b)); });
  $main.querySelectorAll('#mode button').forEach(b => b.onclick = () => { mode = b.dataset.m; $main.querySelectorAll('#mode button').forEach(x => x.classList.toggle('on', x === b)); });
  document.getElementById('ok').onclick = () => {
    const v = i => document.getElementById(i).value; const n = +v('sum');
    if (!v('sum')) { toast('Суммани киритинг'); return; }
    confirmSheet('🛠 Корректировка', `${esc(document.getElementById('o').selectedOptions[0].textContent.split(' — ')[0])} · ${mt} · ${mode === 'delta' ? sign(n) : '= ' + fmt(n)} · ${dShort(v('d'))}<br>${esc(v('reason'))}`, async () => {
      try { const body = { owner: v('o'), mt, reason: v('reason'), date: v('d') }; if (mode === 'delta') body.amount = n; else body.target = n; const r = await post('/admin/moliya/adjust', body); toast(`✅ ${sign(r.sum)} · энди ${fmt(r.after)}`); go('#/sozlama'); } catch (e) { toast(e.message); }
    });
  };
}

/* ================= 🔗 MOYSKLAD ================= */
async function moysklad(view) {
  if (view === 'token') {
    const d = await api('/admin/ms/token');
    setTitle('MoySklad API', d.ok ? '🟢 уланган' : '🔴 уланмаган');
    $main.innerHTML = `<div class="card"><div class="kv"><dt>Жорий калит</dt><dd class="mono">${esc(d.masked || 'киритилмаган')}</dd><dt>Ҳолат</dt><dd>${d.ok ? '🟢 API жавоб беряпти' : '🔴 калит йўқ ёки яроқсиз'}</dd>${d.last403 ? `<dt>Охирги 403</dt><dd>${d.last403}</dd>` : ''}</div>
      <div class="field"><label>Янги калит</label><input id="t" placeholder="MoySklad access token"></div><button class="btn main" id="ok">💾 Сақлаш ва текшириш</button></div>`;
    document.getElementById('ok').onclick = async () => { try { const r = await put('/admin/ms/token', { token: document.getElementById('t').value }); toast(r.ok ? '✅ Сақланди, API жавоб берди' : '⚠️ Сақланди, лекин API жавоб бермади'); render(); } catch (e) { toast(e.message); } };
    return;
  }
  if (view === 'names') {
    const d = await api('/admin/ms/names');
    setTitle('Номлар', d.msOk ? `${d.diffs.length} та фарқ` : '⚠️ MoySklad ўқилмади');
    $main.innerHTML = `${d.diffs.length ? `<div class="card"><h3>MoySklad'дан янгилаш</h3>${d.diffs.map(x => `<div>${esc(x.from)} → <b>${esc(x.to)}</b></div>`).join('')}<button class="btn main" id="apply">🔄 Қўллаш</button></div>` : ''}
      <div class="rows">${d.items.map(x => `<div class="row"><div class="t"><b>${x.kind === 'kassa' ? '🏪' : '📲'} ${esc(x.name)}${x.locked ? ' 🔒' : ''}</b><span>${x.link ? esc(x.link) : 'боғланмаган'}${x.msName && x.msName !== x.name ? ' · MoySklad: ' + esc(x.msName) : ''}</span></div><button class="btn sm ghost" data-ren="${x.key}">✏️</button>${x.locked ? `<button class="btn sm ghost" data-unlock="${x.key}">🔓</button>` : ''}</div>`).join('')}</div>
      <div class="hint">🔒 — қўлда қўйилган ном, MoySklad янгилаши тегмайди. 🔓 — MoySklad номига қайтариш.</div>`;
    document.getElementById('apply')?.addEventListener('click', async () => { try { const r = await post('/admin/ms/names/apply'); toast(`✅ ${r.updated} та янгиланди`); render(); } catch (e) { toast(e.message); } });
    $main.querySelectorAll('[data-ren]').forEach(b => b.onclick = () => { const x = d.items.find(i => i.key === b.dataset.ren); sheet(`<h2>✏️ ${esc(x.name)}</h2><div class="field"><label>Янги ном (2–40)</label><input id="n" value="${esc(x.name)}" maxlength="40"></div><button class="btn main" id="ok">Сақлаш 🔒</button>`, (el, close) => { el.querySelector('#ok').onclick = async () => { try { await put('/admin/ms/names', { key: x.key, name: el.querySelector('#n').value }); close(); toast('✅'); render(); } catch (e) { toast(e.message); } }; }); });
    $main.querySelectorAll('[data-unlock]').forEach(b => b.onclick = async () => { try { const r = await put('/admin/ms/names', { key: b.dataset.unlock, name: '' }); toast('🔓 ' + r.name); render(); } catch (e) { toast(e.message); } });
    return;
  }
  if (view === 'diag') {
    const d = await api('/admin/ms/diag');
    setTitle('Диагностика', d.ok ? '✅ ҳаммаси жойида' : 'муаммолар топилди');
    $main.innerHTML = (d.ok ? `<div class="card"><div class="empty">✅ Минус баланс ҳам, минус кун ҳам, такрор рақам ҳам топилмади.</div></div>` : '')
      + (d.minus.length ? `<div class="label">Минус баланслар</div><div class="rows">${d.minus.map(m => rowHtml('bad', m.name, m.mt === 'NAQD' ? '💵 нақд' : '📲 click', fmt(m.amount), '#/sozlama/moliya/adjust')).join('')}</div>` : '')
      + (d.days.length ? `<div class="label">Минус кунлар</div><div class="rows">${d.days.map(x => rowHtml('warn', x.kassa, dShort(x.date) + (x.mt === 'NAQD' ? ' · 💵' : ' · 📲'), fmt(x.amount))).join('')}</div>` : '')
      + (d.dups.length ? `<div class="label">Бир хил рақамли фойдаланувчилар</div><div class="rows">${d.dups.map(x => rowHtml('warn', x.phone, x.users.join(', '), '')).join('')}</div>` : '')
      + `<div class="hint">Минус сабаблари: MoySklad'да расход бор, кирими бошқа отделга ёзилган; бошланғич қолдиқ йўқ ёки санаси нотўғри; корректировка хато; кунлик қоплаш тушумдан ортиқ. Аввал MoySklad ҳужжатларини текширинг, кейин 🛠 Корректировка.</div>`;
    bindGo();
    return;
  }
  const d = await api('/admin/ms/reload');
  setTitle('Қайта юклаш', d.epoch ? 'ledger: ' + dUz(d.epoch) : '⚠️ ledger санаси йўқ');
  $main.innerHTML = `<div class="card"><p>Бу амал барча операцияларни (<b>${d.ops}</b> та), кун ёзувларини ва ҳисоботларни <b>ЎЧИРАДИ</b>, балансларни 0 га туширади (бошланғич қолдиқ ва корректировкалар ҳам!), MoySklad'дан ${d.epoch ? dUz(d.epoch) : '…'} дан бугунгача қайта тортади. Фойдаланувчи, касса, Click ҳисоблари, қарз дафтари ва созламаларга тегилмайди.</p>
    <p class="hint">Ортга қайтариб бўлмайди. Натижа чатингизга келади.</p><button class="btn danger" id="ok" ${d.epoch ? '' : 'disabled'}>📥 Ҳа, ўчириб қайта юкла</button></div>`;
  document.getElementById('ok').onclick = () => confirmSheet('📥 Қайта юклаш', 'Ҳамма молиявий маълумот ўчиб, MoySklad\'дан қайта юкланади. Тасдиқлайсизми?', async () => { try { await post('/admin/ms/reload'); toast('⏳ Бошланди, чатга хабар келади'); go('#/sozlama'); } catch (e) { toast(e.message); } });
}

/* ================= 📋 АУДИТ ================= */
async function auditPage(q) {
  const user = q.user || '0';
  const d = await api(`/admin/audit?user=${user}&limit=100`);
  setTitle('Аудит', `${d.total} ёзув · охирги ${d.rows.length}`);
  $main.innerHTML = `<div class="kpis"><div class="field"><label>Ким</label><select id="u"><option value="0">Ҳаммаси</option>${d.users.map(x => `<option value="${x.id}" ${String(x.id) === user ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}</select></div><div class="field"><label>&nbsp;</label><button class="btn ghost" id="xl">📥 Excel (чатга)</button></div></div>
    <div class="tablewrap"><table><thead><tr><th>Вақт</th><th>Ким</th><th>Амал</th><th>Объект</th><th>Тафсилот</th></tr></thead><tbody>${d.rows.map(r => `<tr><td>${r.at}</td><td>${esc(r.user)}</td><td><b>${esc(r.action)}</b></td><td>${esc(r.entity)}</td><td style="white-space:normal;min-width:220px">${esc(r.payload)}</td></tr>`).join('')}</tbody></table></div>`;
  document.getElementById('u').onchange = e => go('#/sozlama/audit?user=' + e.target.value);
  document.getElementById('xl').onclick = async () => { try { await post('/admin/audit/excel', { userId: +user }); toast('📤 Чатга юборилмоқда'); } catch (e) { toast(e.message); } };
}

/* ---- 🧩 Меню тартиби ---- */
async function sozMenyu(key) {
  const menus = await api('/admin/settings/menu');
  if (!key) {
    setTitle('Меню тартиби', menus.length + ' та меню');
    $main.innerHTML = `<div class="rows">${menus.map(m => rowHtml(m.customized ? 'warn' : 'ok', m.title,
      m.items.map(i => i.display).join(' · '), m.customized ? '✏️' : '', `#/sozlama/menyu/${m.key}`)).join('')}</div>
      <div class="hint">✏️ — коддаги стандартдан фарқ қилади. Созланмаган меню код кўринишида қолади.</div>`;
    bindGo();
    return;
  }
  const m = menus.find(x => x.key === key);
  if (!m) { go('#/sozlama/menyu'); return; }
  setTitle(m.title, `${m.cols} устун · ${m.customized ? 'созланган' : 'стандарт'}`);
  let order = m.items.map(i => i.canonical);
  const draw = () => {
    $main.innerHTML = `<div class="rows" id="list">${order.map((c, i) => {
      const it = m.items.find(x => x.canonical === c);
      return `<div class="row ${it.hidden ? 'bad' : 'ok'}" draggable="true" data-i="${i}">
        <span class="chev" style="cursor:grab">⋮⋮</span>
        <div class="t"><b>${i + 1}. ${it.hidden ? '🙈 ' : ''}${esc(it.display)}</b><span>${it.renamed ? 'асл: ' + esc(it.canonical) : ''}</span></div>
        <button class="btn sm ghost" data-up="${i}" ${i === 0 ? 'disabled' : ''}>⬆️</button>
        <button class="btn sm ghost" data-down="${i}" ${i === order.length - 1 ? 'disabled' : ''}>⬇️</button>
        ${it.renamable && !it.protected ? `<button class="btn sm ghost" data-hide="${c}">${it.hidden ? '👁' : '🙈'}</button>` : ''}</div>`;
    }).join('')}</div>
    <div class="seg">${[1, 2, 3].map(n => `<button class="${m.cols === n ? 'on' : ''}" data-cols="${n}">${n} устун</button>`).join('')}</div>
    <div class="actions"><button class="btn main" id="save">💾 Сақлаш</button>${m.customized ? '<button class="btn ghost" id="reset">♻️ Асл тартибга қайтариш</button>' : ''}</div>
    <div class="hint">Сақлангач фойдаланувчиларда меню қайта очилганда (/start) кўринади.</div>`;
    const move = (i, j) => { if (j < 0 || j >= order.length) return; const t = order[i]; order.splice(i, 1); order.splice(j, 0, t); draw(); };
    $main.querySelectorAll('[data-up]').forEach(b => b.onclick = () => move(+b.dataset.up, +b.dataset.up - 1));
    $main.querySelectorAll('[data-down]').forEach(b => b.onclick = () => move(+b.dataset.down, +b.dataset.down + 1));
    $main.querySelectorAll('[data-hide]').forEach(b => b.onclick = async () => {
      const it = m.items.find(x => x.canonical === b.dataset.hide);
      try { await put('/admin/settings/labels', { canonical: it.canonical, hidden: !it.hidden }); it.hidden = !it.hidden; haptic(); draw(); } catch (e) { toast(e.message); }
    });
    $main.querySelectorAll('[data-cols]').forEach(b => b.onclick = () => { m.cols = +b.dataset.cols; draw(); });
    let dragFrom = null;
    $main.querySelectorAll('[draggable]').forEach(el => {
      el.addEventListener('dragstart', () => { dragFrom = +el.dataset.i; });
      el.addEventListener('dragover', e => e.preventDefault());
      el.addEventListener('drop', e => { e.preventDefault(); if (dragFrom !== null) move(dragFrom, +el.dataset.i); dragFrom = null; });
    });
    document.getElementById('save').onclick = async () => {
      try { await put('/admin/settings/menu/' + key, { order, cols: m.cols }); haptic('medium'); toast('✅ Сақланди'); go('#/sozlama/menyu'); } catch (e) { toast(e.message); }
    };
    document.getElementById('reset')?.addEventListener('click', () => confirmSheet('Асл тартиб', 'Бу меню код кўринишига қайтади.', async () => {
      try { await post('/admin/settings/menu/' + key + '/reset'); toast('♻️ Қайтарилди'); go('#/sozlama/menyu'); } catch (e) { toast(e.message); }
    }));
  };
  draw();
}

/* ---- 🏷 Тугма номлари ---- */
async function sozNomlar() {
  const labels = await api('/admin/settings/labels');
  setTitle('Тугма номлари', labels.filter(l => l.renamed).length + ' та ўзгартирилган');
  $main.innerHTML = `<div class="rows">${labels.map(l => `<div class="row ${l.hidden ? 'bad' : 'ok'}">
      <div class="t"><b>${l.hidden ? '🙈 ' : ''}${esc(l.display)}${l.renamed ? ' *' : ''}</b><span>${l.renamed ? 'асл: ' + esc(l.canonical) : (l.protected ? 'яшириб бўлмайди' : '')}</span></div>
      <button class="btn sm ghost" data-ren="${esc(l.canonical)}">✏️</button>
      ${l.protected ? '' : `<button class="btn sm ghost" data-hide="${esc(l.canonical)}">${l.hidden ? '👁' : '🙈'}</button>`}</div>`).join('')}</div>
    <div class="hint">* — номи ўзгартирилган · 🙈 — менюларда кўринмайди. Кодда ҳамма жойда асл ном ишлатилади, шунинг учун навигация бузилмайди.</div>`;
  $main.querySelectorAll('[data-ren]').forEach(b => b.onclick = () => {
    const l = labels.find(x => x.canonical === b.dataset.ren);
    sheet(`<h2>✏️ ${esc(l.canonical)}</h2><div class="field"><label>Янги ном (2–30 белги, бўш — асл номга қайтариш)</label><input id="nm" value="${esc(l.renamed ? l.display : '')}" maxlength="30"></div><button class="btn main" id="ok">Сақлаш</button>`,
      (el, close) => { el.querySelector('#ok').onclick = async () => { try { await put('/admin/settings/labels', { canonical: l.canonical, name: el.querySelector('#nm').value }); close(); toast('✅ Сақланди'); render(); } catch (e) { toast(e.message); } }; });
  });
  $main.querySelectorAll('[data-hide]').forEach(b => b.onclick = async () => {
    const l = labels.find(x => x.canonical === b.dataset.hide);
    try { await put('/admin/settings/labels', { canonical: l.canonical, hidden: !l.hidden }); haptic(); render(); } catch (e) { toast(e.message); }
  });
}

/* ---- 👁 Ҳуқуқлар ---- */
async function sozHuquq(subj, id) {
  if (!subj) {
    const d = await api('/admin/settings/perm');
    setTitle('Ҳуқуқлар', 'ходим ёки отделни танланг');
    $main.innerHTML = `<div class="label">Ходимлар</div><div class="rows">${d.users.map(u => rowHtml(u.configured ? 'warn' : 'ok', u.name, (u.role === 'KASSIR' ? 'кассир' : 'бухгалтер') + (u.configured ? ' · алоҳида созланган' : ' · мерос'), '', `#/sozlama/huquq/user/${u.id}`)).join('')}</div>
      <div class="label">Отделлар (шу отдел кассирлари)</div><div class="rows">${d.kassas.map(k => rowHtml(k.configured ? 'warn' : 'ok', k.name, k.configured ? 'алоҳида созланган' : 'мерос', '', `#/sozlama/huquq/kassa/${k.id}`)).join('')}</div>
      <div class="hint">Қоида: ходим учун белгиланган → отдел учун белгиланган → умумий (яширилган/очиқ). Алоҳида созланган ходим/отделда фақат ✅ берилган бўлимлар кўринади.</div>`;
    bindGo();
    return;
  }
  const d = await api(`/admin/settings/perm/${subj}/${id}`);
  setTitle(subj === 'user' ? 'Ходим ҳуқуқлари' : 'Отдел ҳуқуқлари', d.configured ? 'алоҳида созланган' : 'мерос (умумий ҳолат)');
  const cyc = { null: 'мерос', true: '✅ рухсат', false: '🚫 тақиқ' };
  $main.innerHTML = `<div class="rows">${d.rows.map(r => `<div class="row ${r.override === false ? 'bad' : r.override === true ? 'ok' : ''}">
      <div class="t"><b>${esc(r.display)}</b><span>${r.effective ? 'кўради' : 'кўрмайди'}</span></div>
      <button class="btn sm ghost" data-c="${esc(r.canonical)}" data-s="${r.override}">${cyc[r.override]}</button></div>`).join('')}</div>
    <div class="hint">Тугма цикли: мерос → 🚫 тақиқ → ✅ рухсат → мерос.</div>`;
  $main.querySelectorAll('[data-c]').forEach(b => b.onclick = async () => {
    const cur = b.dataset.s; const next = cur === 'null' ? false : cur === 'false' ? true : null;
    try { await put('/admin/settings/perm', { subj, id: +id, canonical: b.dataset.c, state: next }); haptic(); render(); } catch (e) { toast(e.message); }
  });
}

/* ---- 🔔 Билдиришномалар ---- */
async function sozShablon(id, q) {
  if (id === 'namuna') {
    const ps = await api('/admin/notify/presets');
    setTitle('Намуналар', 'тайёр ҳисобот шаблонлари');
    $main.innerHTML = `<div class="rows">${ps.map(p => `<div class="row ok"><div class="t"><b>${esc(p.title)}</b><span>${esc(p.about)}</span></div><button class="btn sm main" data-k="${p.key}">➕</button></div>`).join('')}</div>
      <div class="hint">Яратилган шаблон ўчирилган ҳолда бўлади — матн, жадвал ва қабул қилувчиларни текшириб ёқасиз. Асл ҳисоботлар (код) ишлайверади.</div>`;
    $main.querySelectorAll('[data-k]').forEach(b => b.onclick = async () => { try { const n = await post('/admin/notify/presets/' + b.dataset.k); toast('✅ Яратилди'); go('#/sozlama/shablon/' + n.id); } catch (e) { toast(e.message); } });
    return;
  }
  if (id === 'yordam') {
    const h = await api('/admin/notify/help');
    setTitle('Ўринбосарлар', 'шаблон синтаксиси');
    $main.innerHTML = h.pages.map(p => `<div class="card" style="white-space:pre-wrap;font-size:13px">${p.replace(/<\/?(b|i|code)>/g, m => m)}</div>`).join('');
    return;
  }
  if (!id) {
    const [list, help] = await Promise.all([api('/admin/notify'), api('/admin/notify/help')]);
    setTitle('Билдиришномалар', list.length + ' та шаблон');
    $main.innerHTML = `<div class="rows">${list.map(n => rowHtml(n.active ? 'ok' : '', n.name, n.scheduleText + ' · ' + n.recipientsText.replace(/<[^>]+>/g, '') + (n.lastError ? ' · ⚠️ ' + n.lastError : ''), n.active ? '🟢' : '⚪', `#/sozlama/shablon/${n.id}`)).join('') || '<div class="card"><div class="empty">Ҳали шаблон йўқ.</div></div>'}</div>
      <div class="actions"><button class="btn main" id="new">➕ Янги шаблон</button><button class="btn ghost" data-go="#/sozlama/shablon/namuna">📚 Намуналар</button><button class="btn ghost" data-go="#/sozlama/shablon/yordam">📖 Ўринбосарлар</button></div>
      <div class="card"><div class="kv"><dt>🗑 Тасдиқ хабари («қабул қилинди») ўчирилиши</dt><dd><input id="cd" type="number" min="0" max="1440" value="${help.confirmDeleteMin}" style="width:70px;text-align:right;font:inherit;background:var(--surface-2);border:1px solid var(--line);border-radius:8px;padding:4px 8px;color:var(--text)"> мин</dd></div><div class="hint">0 — ўчирилмайди. «✏️ Tuzatish» босилса таймер тўхтайди, тузатилгач 0 дан бошланади.</div></div>`;
    bindGo();
    document.getElementById('new').onclick = async () => { try { const n = await post('/admin/notify', { name: 'Янги шаблон', template: '' }); go('#/sozlama/shablon/' + n.id); } catch (e) { toast(e.message); } };
    document.getElementById('cd').onchange = async e => { try { await put('/admin/notify/confirm-delete', { min: +e.target.value }); toast('✅ Сақланди'); } catch (er) { toast(er.message); } };
    return;
  }
  const n = await api('/admin/notify/' + id);
  setTitle(n.name, (n.active ? '🟢 фаол' : '⚪ ўчирилган') + (n.next ? ' · кейинги ' + n.next.replace('T', ' ').slice(0, 16) : ''));
  const wk = (n.weekdays || '').split(',').filter(Boolean).map(Number);
  const DAYS = ['Ду', 'Се', 'Чо', 'Па', 'Жу', 'Ша', 'Як'];
  $main.innerHTML = `<div class="card">
      <div class="field"><label>Ном</label><input id="f-name" value="${esc(n.name)}" maxlength="80"></div>
      <div class="field"><label>Шаблон матни (HTML: b, i, code; ўринбосарлар — 📖)</label><textarea id="f-tpl" rows="9" style="font-family:ui-monospace,Consolas,monospace;font-size:13px">${esc(n.template)}</textarea></div>
      <div class="actions"><button class="btn ghost" id="prev">👁 Кўриниш</button></div>
      <div id="prevbox" hidden class="card" style="background:var(--surface-2);white-space:pre-wrap;font-size:13px"></div>
    </div>
    <div class="card">
      <div class="field"><label>⏰ Жадвал — «09:00, 13:00» ёки «har 2 soat 09-21 +15» ёки «once:2026-09-05T14:30»</label><input id="f-sched" value="${esc(n.schedule)}"></div>
      <div class="field"><label>Ҳафта кунлари (бўш — ҳар куни)</label><div class="seg" id="wk">${DAYS.map((d, i) => `<button class="${wk.includes(i + 1) ? 'on' : ''}" data-d="${i + 1}">${d}</button>`).join('')}</div></div>
      <div class="field"><label>👥 Кимга — group:-100…, rol:KASSIR, user:5, kassa:2, karta_masul, click_chats, mehmonlar (вергул билан)</label><input id="f-rec" value="${esc(n.recipients)}"></div>
      <div class="hint">Ҳозир: ${n.recipientsText}</div>
      <div class="kpis">
        <div class="field"><label>🗑 Авто-ўчириш, мин (0 — йўқ)</label><input id="f-del" type="number" min="0" max="1440" value="${n.autoDeleteMin}"></div>
        <div class="field"><label>🔘 Меню тугмаси матни</label><input id="f-btn" value="${esc(n.buttonLabel)}" maxlength="40" placeholder="бўш — тугма йўқ"></div>
      </div>
      <div class="field"><label>Тугма роллари — kassir, bux, admin</label><input id="f-roles" value="${esc(n.buttonRoles)}"></div>
    </div>
    <div class="actions">
      <button class="btn main" id="save">💾 Сақлаш</button>
      <button class="btn ghost" id="toggle">${n.active ? '⏸ Ўчириб туриш' : '▶️ Ёқиш'}</button>
      <button class="btn ghost" id="test">🧪 Тест (ўзимга)</button>
      <button class="btn ghost" id="sendnow">🚀 Ҳозир юбориш</button>
      <button class="btn danger" id="del">🗑 Ўчириш</button>
    </div>
    ${n.lastSent ? `<div class="hint">📤 Охирги: ${esc(n.lastSent.replace('T', ' '))}${n.lastError ? ' · ⚠️ ' + esc(n.lastError) : ''}</div>` : ''}`;
  const wkSel = () => [...$main.querySelectorAll('#wk button.on')].map(b => b.dataset.d).join(',');
  $main.querySelectorAll('#wk button').forEach(b => b.onclick = () => b.classList.toggle('on'));
  const body = () => ({ name: v('f-name'), template: v('f-tpl'), schedule: v('f-sched'), weekdays: wkSel(), recipients: v('f-rec'), autoDeleteMin: +v('f-del'), buttonLabel: v('f-btn'), buttonRoles: v('f-roles') });
  const v = i => document.getElementById(i).value;
  document.getElementById('prev').onclick = async () => {
    try { const r = await post('/admin/notify/preview', { template: v('f-tpl') }); const box = document.getElementById('prevbox'); box.hidden = false; box.innerHTML = (r.text || '<i>(бўш)</i>') + (r.unknown?.length ? `<div class="hint" style="margin-top:8px">⚠️ Номаълум: ${esc(r.unknown.join(' '))}</div>` : '') + (r.msFailed ? '<div class="hint">⚠️ MoySklad ўқилмади</div>' : ''); } catch (e) { toast(e.message); }
  };
  document.getElementById('save').onclick = async () => { try { const r = await put('/admin/notify/' + id, body()); haptic('medium'); toast(r.unknown?.length ? '✅ Сақланди · ⚠️ номаълум: ' + r.unknown.join(' ') : '✅ Сақланди'); render(); } catch (e) { toast(e.message); } };
  document.getElementById('toggle').onclick = async () => { try { await put('/admin/notify/' + id, { active: !n.active }); render(); } catch (e) { toast(e.message); } };
  document.getElementById('test').onclick = async () => { try { await put('/admin/notify/' + id, body()); await post('/admin/notify/' + id + '/send?test=true'); toast('🧪 Чатингизга юборилди'); } catch (e) { toast(e.message); } };
  document.getElementById('sendnow').onclick = () => confirmSheet('🚀 Ҳозир юбориш', 'Шаблон барча қабул қилувчиларга ҳозир кетади.', async () => { try { await put('/admin/notify/' + id, body()); const r = await post('/admin/notify/' + id + '/send'); toast('🚀 ' + (r.result || 'юборилди')); render(); } catch (e) { toast(e.message); } });
  document.getElementById('del').onclick = () => confirmSheet('🗑 Ўчириш', `«${esc(n.name)}» бутунлай ўчирилади.`, async () => { try { await api('/admin/notify/' + id, { method: 'DELETE' }); toast('Ўчирилди'); go('#/sozlama/shablon'); } catch (e) { toast(e.message); } });
}


/* ================= 🧩 БОТ СXЕМАСИ — drag-and-drop муҳаррири =================
   Эркин менюлар (бош меню, Ҳисоботлар, Настройка ва гуруҳлари): тугмаларни менюлар
   орасида судраб кўчириш, тартиблаш, яшириш. Контекстли менюлар (кассир, КАССАМ,
   касса картаси): фақат тартиб/яшириш. Сақлаш — PUT /admin/settings/schema. */
async function sozSxema() {
  const menus = await api('/admin/settings/menu');
  setTitle('Бот сxемаси', 'судраб кўчиринг · ⬆⬇ · 🙈 яшириш · сақлашни унутманг');
  const model = menus.map(m => ({ ...m, items: m.items.map(i => ({ ...i })) }));   // ишчи нусха
  const info = {}; model.forEach(m => m.items.forEach(i => { info[i.canonical] = i; }));
  let dirty = false;

  const draw = () => {
    const free = model.filter(m => m.free), ctx = model.filter(m => !m.free);
    const block = m => `<div class="menu-block" data-menu="${m.key}">
        <div class="mh"><b>${esc(m.title)}</b><small>${m.items.length} та · ${m.cols} устун${m.customized ? ' · ✏️' : ''}</small></div>
        <div class="items" data-menu="${m.key}">${m.items.map((it, i) => item(m, it, i)).join('') || '<div class="empty" style="padding:6px 12px">бўш — бу ерга судранг</div>'}</div></div>`;
    const item = (m, it, i) => `<div class="mi" data-menu="${m.key}" data-i="${i}" data-c="${esc(it.canonical)}">
        <span class="h" data-drag="1">⋮⋮</span>
        <div class="t ${it.hidden ? 'hidden' : ''}">${esc(it.display)}${it.submenu ? ' ▸' : ''}<small>${it.submenu ? 'остменю' : it.saOnly ? 'фақат SuperAdmin' : 'амал'}${it.pinned ? ' · қотирилган' : ''}${it.renamed ? ' · асл: ' + esc(it.canonical) : ''}</small></div>
        <button class="ib" data-up="${i}" ${i === 0 ? 'disabled' : ''}>↑</button>
        <button class="ib" data-down="${i}" ${i === m.items.length - 1 ? 'disabled' : ''}>↓</button>
        ${m.free && !it.pinned ? `<button class="ib" data-move="${i}" title="Бошқа менюга">↪</button>` : ''}
        ${it.renamable && !it.protected ? `<button class="ib" data-hide="${esc(it.canonical)}">${it.hidden ? '👁' : '🙈'}</button>` : ''}
      </div>
      ${it.renamable ? `<div class="mi-roles" data-c="${esc(it.canonical)}">
        ${roleChip('KASSIR', 'Кассир', it, m)}${roleChip('BUXGALTER', 'Бухгалтер', it, m)}
        <span class="rc sa">SuperAdmin ✓</span>
        <button class="rc ex" data-ex="${esc(it.canonical)}">👤 ${it.userExceptions ? it.userExceptions + ' истисно' : 'истисно…'}</button>
      </div>` : ''}`;
    $main.innerHTML = `<div class="schema">
        <div class="hint">Эркин менюларда тугмани бошқа менюга судраб ташлаш мумкин — бот уни қаерда турса ҳам танийди. «⚙️ Настройка», «🏪 Кассалар», «🤝 КОНТРАГЕНТ» қотирилган. Яширилган тугма менюда чиқмайди, лекин ўрни сақланади.<br>Роллар: чип босилса мерос → ✗ тақиқ → ✓ рухсат → мерос. Тартиб: ходим истисноси → отдел → рол → умумий. SuperAdmin ҳаммасини кўради.</div>
        <div class="label">Эркин менюлар</div>${free.map(block).join('')}
        <div class="label">Контекстли менюлар (фақат тартиб)</div>${ctx.map(block).join('')}
        <div class="savebar"><button class="btn main" id="save" ${dirty ? '' : 'disabled'}>💾 Сақлаш</button><button class="btn ghost" id="reset">♻️ Асл</button></div>
      </div>`;
    bind();
  };

  /* Рол чипи: null — мерос (менюга кўра), true — рухсат, false — тақиқ. SA-only тугмада қулф. */
  const roleChip = (role, name, it, m) => {
    const v = it.roles?.[role];
    const locked = it.saOnly;
    const isKassirMenu = m.key === 'main.kassir' || m.key === 'kassam';
    const inherit = locked ? false : (role === 'KASSIR' ? isKassirMenu : !isKassirMenu);   // меросдаги кўриниш
    const eff = v == null ? inherit : v;
    return `<button class="rc ${eff ? 'on' : 'off'} ${v == null ? 'inh' : ''}" data-role="${role}" data-c="${esc(it.canonical)}" ${locked ? 'disabled title="фақат SuperAdmin"' : ''}>${name} ${eff ? '✓' : '✗'}${v == null ? '' : ' •'}</button>`;
  };
  const find = key => model.find(m => m.key === key);
  const markDirty = () => { dirty = true; draw(); };
  const moveItem = (fromKey, i, toKey, j) => {
    const a = find(fromKey), b = find(toKey);
    const [it] = a.items.splice(i, 1);
    if (fromKey === toKey && j > i) j--;
    b.items.splice(Math.max(0, Math.min(j, b.items.length)), 0, it);
    markDirty();
  };

  function bind() {
    $main.querySelectorAll('[data-up]').forEach(b => b.onclick = () => { const m = find(b.closest('.mi').dataset.menu), i = +b.dataset.up; [m.items[i - 1], m.items[i]] = [m.items[i], m.items[i - 1]]; markDirty(); });
    $main.querySelectorAll('[data-down]').forEach(b => b.onclick = () => { const m = find(b.closest('.mi').dataset.menu), i = +b.dataset.down; [m.items[i + 1], m.items[i]] = [m.items[i], m.items[i + 1]]; markDirty(); });
    $main.querySelectorAll('[data-move]').forEach(b => b.onclick = () => {
      const fromKey = b.closest('.mi').dataset.menu, i = +b.dataset.move, it = find(fromKey).items[i];
      sheet(html`<h2>↪ ${esc(it.display)}</h2><div class="field"><label>Қайси менюга</label><select id="to">${model.filter(m => m.free && m.key !== fromKey).map(m => `<option value="${m.key}">${esc(m.title)}</option>`).join('')}</select></div><button class="btn main" id="ok">Кўчириш</button>`,
        (el, close) => { el.querySelector('#ok').onclick = () => { moveItem(fromKey, i, el.querySelector('#to').value, 999); close(); }; });
    });
    $main.querySelectorAll('[data-role]').forEach(b => b.onclick = async () => {
      const it = info[b.dataset.c], role = b.dataset.role, cur = it.roles?.[role];
      const next = cur == null ? false : cur === false ? true : null;   // мерос → ✗ → ✓ → мерос
      try { const r = await put('/admin/settings/perm/role', { role, canonical: it.canonical, state: next }); it.roles = it.roles || {}; it.roles[role] = next; haptic(); draw(); } catch (e) { toast(e.message); }
    });
    $main.querySelectorAll('[data-ex]').forEach(b => b.onclick = () => go('#/sozlama/huquq'));
    $main.querySelectorAll('[data-hide]').forEach(b => b.onclick = async () => {
      const it = info[b.dataset.hide];
      try { await put('/admin/settings/labels', { canonical: it.canonical, hidden: !it.hidden }); it.hidden = !it.hidden; haptic(); draw(); } catch (e) { toast(e.message); }
    });
    document.getElementById('save').onclick = async () => {
      const body = {}; model.forEach(m => { body[m.key] = m.items.map(i => i.canonical); });
      try { await put('/admin/settings/schema', { menus: body }); haptic('medium'); toast('✅ Сақланди — фойдаланувчиларда /start дан кейин'); dirty = false; render(); } catch (e) { toast(e.message); }
    };
    document.getElementById('reset').onclick = () => confirmSheet('♻️ Асл сxема', 'Барча менюлар код тартибига қайтади.', async () => {
      try { for (const m of model) await post('/admin/settings/menu/' + m.key + '/reset'); toast('♻️'); render(); } catch (e) { toast(e.message); }
    });
    dnd();
  }

  /* Pointer-асосли drag-and-drop: телефонда ҳам, компьютерда ҳам. Ҳужжат тингловчилари
     БИР МАРТА ўрнатилади (DND.ctx орқали жорий саҳифа моделига мурожаат қилади). */
  function dnd() {
    DND.ctx = { find, moveItem, root: $main };
    $main.querySelectorAll('.mi .h').forEach(h => h.addEventListener('pointerdown', e => {
      const mi = h.closest('.mi'); const m = find(mi.dataset.menu);
      DND.drag = { fromKey: mi.dataset.menu, i: +mi.dataset.i, el: mi, free: m.free }; DND.target = null;
      mi.classList.add('dragging');
      DND.ghost = document.createElement('div'); DND.ghost.className = 'ghost'; DND.ghost.textContent = mi.querySelector('.t').firstChild.textContent;
      document.body.appendChild(DND.ghost); dndGhost(e);
      h.setPointerCapture(e.pointerId); e.preventDefault();
    }));
  }
  draw();
}

const DND = { drag: null, ghost: null, target: null, ctx: null };
const dndGhost = e => { if (DND.ghost) { DND.ghost.style.left = (e.clientX + 12) + 'px'; DND.ghost.style.top = (e.clientY - 14) + 'px'; } };
const dndClear = () => { document.querySelectorAll('.drop-before,.drop-after').forEach(x => x.classList.remove('drop-before', 'drop-after')); document.querySelectorAll('.items.over').forEach(x => x.classList.remove('over')); };
document.addEventListener('pointermove', e => {
  const d = DND.drag, c = DND.ctx; if (!d || !c) return;
  dndGhost(e); dndClear();
  const el = document.elementFromPoint(e.clientX, e.clientY); if (!el) return;
  const mi = el.closest('.mi'), items = el.closest('.items');
  const allowed = toKey => d.free ? c.find(toKey).free : toKey === d.fromKey;
  if (mi && mi !== d.el) {
    const toKey = mi.dataset.menu; if (!allowed(toKey)) return;
    const r = mi.getBoundingClientRect(); const pos = e.clientY < r.top + r.height / 2 ? 'before' : 'after';
    mi.classList.add(pos === 'before' ? 'drop-before' : 'drop-after'); DND.target = { key: toKey, i: +mi.dataset.i, pos };
  } else if (items) {
    const toKey = items.dataset.menu; if (!allowed(toKey)) return;
    items.classList.add('over'); DND.target = { key: toKey, i: c.find(toKey).items.length, pos: 'before' };
  }
  if (e.clientY < 90) window.scrollBy(0, -8); else if (e.clientY > innerHeight - 40) window.scrollBy(0, 8);
});
document.addEventListener('pointerup', () => {
  const d = DND.drag, c = DND.ctx; if (!d || !c) return;
  dndClear(); DND.ghost?.remove(); DND.ghost = null; d.el.classList.remove('dragging');
  const t = DND.target;
  if (t) { const it = c.find(d.fromKey).items[d.i]; if (it.pinned && t.key !== d.fromKey) toast('Бу тугма қотирилган'); else c.moveItem(d.fromKey, d.i, t.key, t.pos === 'before' ? t.i : t.i + 1); }
  DND.drag = null; DND.target = null;
});

/* ---------------------------- тема (☀️ / 🌙 / 🅰 авто) ---------------------------- */
const THEMES = ['auto', 'light', 'dark'];
const THEME_ICON = { auto: '🅰', light: '☀️', dark: '🌙' };
function readTheme() { try { return localStorage.getItem('kn.theme') || 'auto'; } catch (_) { return 'auto'; } }
function applyTheme(t) {
  const root = document.documentElement;
  if (t === 'auto') root.removeAttribute('data-theme'); else root.setAttribute('data-theme', t);
  document.getElementById('theme').textContent = THEME_ICON[t];
  // Telegram сарлавҳа/фон рангини ҳам мослаш — қора режимда оқ ҳошия қолмасин
  try {
    const dark = t === 'dark' || (t === 'auto' && tg?.colorScheme === 'dark');
    const bg = t === 'auto' ? (tg?.themeParams?.bg_color || (dark ? '#10161c' : '#f2f4f6')) : (dark ? '#10161c' : '#f2f4f6');
    tg?.setHeaderColor?.(bg); tg?.setBackgroundColor?.(bg);
  } catch (_) { /* эски клиент */ }
}
document.getElementById('theme').addEventListener('click', () => {
  const next = THEMES[(THEMES.indexOf(readTheme()) + 1) % THEMES.length];
  try { localStorage.setItem('kn.theme', next); } catch (_) { /* private mode */ }
  haptic(); applyTheme(next);
  toast(next === 'auto' ? '🅰 Telegram темаси' : next === 'light' ? '☀️ Ёруғ режим' : '🌙 Қоронғи режим');
});
applyTheme(readTheme());
try { tg?.onEvent?.('themeChanged', () => applyTheme(readTheme())); } catch (_) { /* */ }

/* ---------------------------- boot ---------------------------- */
document.querySelectorAll('.nav button').forEach(b => b.addEventListener('click', () => {
  haptic();
  go(b.dataset.tab === 'bugun' ? '#/' : '#/' + b.dataset.tab);
}));
window.addEventListener('hashchange', render);
try { tg?.ready(); tg?.expand(); tg?.BackButton?.onClick(back); } catch (_) { /* браузер */ }
document.getElementById('back').addEventListener('click', () => { haptic(); back(); });

(async () => {
  try {
    ME = await api('/me');
    document.getElementById('who').textContent = ME.name + ' · ' + ({ BUXGALTER: 'Бухгалтер', SUPERADMIN: 'SuperAdmin', KASSIR: 'Кассир' }[ME.role] || ME.role);
    const sp = tg?.initDataUnsafe?.start_param;   // deep link: startapp=pending_17 → #/pending/17
    if (sp && !location.hash) { const m = sp.match(/^([a-z]+)_(\d+)$/); if (m) location.hash = `#/${m[1]}/${m[2]}`; }
    render();
  } catch (e) {
    setTitle('Админ панел', '');
    $main.innerHTML = `<div class="err">⛔ ${esc(e.message)}</div>`;
  }
})();
