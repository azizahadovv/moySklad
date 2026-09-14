---
name: miniapp-component
description: Mini App (static/app.js) ga yangi sahifa, jadval, karta, forma, bottom sheet yoki ro'yxat qo'shish qoidalari — mavjud yordamchilar (api, setTitle, rowHtml, tile, sheet, confirmSheet, dateBar, toast) va sahifa shabloni. app.js ni o'zgartirishdan OLDIN o'qi; yangi UI kodi shu naqshlarga yig'iladi, nol dan yozilmaydi.
---

# Mini App komponentlari va sahifa shabloni

`app.js` — 1500+ qator, kutubxonasiz, tuzilma `api → util → router → pages`. Yangi kod tegishli `/* ==== BO'LIM ==== */` bloki ostiga qo'shiladi. Stil uchun `[[miniapp-design-system]]`.

## 1. Router va URL

- Hash route: `#/<tab>/<obyekt>/<ko'rinish>?filtr=...`. ID yo'lda, filtr so'rovda (`#/kassa/12?t=kunlar`, `#/hisobot/kunlik?date=2026-09-12`).
- Tab ildizlari `PAGES`: `'' → pageBugun`, `pending`, `kassa`, `hisobot`, `sozlama`. Yangi tab qo'shilmaydi (4 ta, `.nav` grid 4 ustun). Yangi ekran mavjud tab ichiga bo'lim sifatida kiradi: `pageHisobot(seg, q)` ichida `seg[0]` bo'yicha `hisobotXxx(q)` ga tarqatiladi.
- Yangi bo'lim uchun `CRUMB` xaritasiga kalit → o'zbekcha nom qo'shiladi, aks holda yo'lda xom kalit ko'rinadi.
- Navigatsiya: `go('#/...')`, `back()`. Klik bilan o'tish uchun elementga `data-go="#/..."` beriladi va oxirida `bindGo()` chaqiriladi. `onclick` bilan `go()` yozilmaydi.
- Deep link: `startapp=<seg>_<id>` → `#/<seg>/<id>` (boot da).

## 2. Sahifa shabloni (har yangi ekran shunday)

```js
async function hisobotYangi(q) {
  const date = q.date || TODAY();                       // filtr defaultlari
  const d = await api('/admin/report/yangi?date=' + date);   // bitta so'rov, skeletonni render() ko'rsatadi
  setTitle('Sarlavha', `izoh · ${dUz(d.date)}`);       // crumbs avtomatik
  let h = `<div id="datebar"></div>
    <div class="kpis">...</div>
    <div class="label">Bo'lim nomi</div>
    ${d.rows.length ? `<div class="rows">${d.rows.map(r => rowHtml(...)).join('')}</div>`
                    : `<div class="card"><div class="empty">Bo'sh holat matni.</div></div>`}
    <div class="hint">Formulani/qoidani bir jumlada tushuntirish.</div>
    <div class="actions"><button class="btn main" id="ok">✅ Asosiy amal</button><button class="btn ghost" id="x">📤 Ikkilamchi</button></div>`;
  $main.innerHTML = h;
  bindGo();
  document.getElementById('datebar')?.replaceWith(dateBar(date, iso => go('#/hisobot/yangi?date=' + iso)));
  document.getElementById('ok')?.addEventListener('click', () => confirmSheet('Sarlavha', 'Nima bo'ladi', async () => { try { await post(...); haptic('medium'); toast('✅ Bajarildi'); render(); } catch (e) { toast(e.message); } }));
}
```

Majburiy tartib: **KPI → ro'yxat/jadval → hint → actions**. Sarlavha `setTitle` orqali, `<h1>` yozilmaydi. Xato `render()` da ushlanadi (`.err`), sahifada try/catch faqat amallar uchun.

## 3. Tayyor bloklar — qachon qaysi

| Ehtiyoj | Blok | Yordamchi |
|---|---|---|
| 2–4 asosiy raqam | `.kpis > .kpi` (`.l` yorliq, `.v.num` qiymat, `.s` izoh) | qo'lda HTML |
| Bosiladigan ro'yxat, holat markerli | `.rows > .row` | `rowHtml(cls, title, sub, right, href)` |
| Bo'limga kirish menyusi | `.tiles > .tile` | `tile(icon, title, sub, href, cls)` |
| Ko'p ustunli raqamlar | `.tablewrap > table`, `th.num/td.num`, oxirida `tr.total` | qo'lda HTML |
| Kalit/qiymat kartasi | `.card > .kv` (`dt`/`dd`) | `kvRow(l, v)` |
| Ichki tablar / filtr | `.seg > button.on`, `data-go` bilan | qo'lda |
| Sana tanlash | `.seg` ichida ‹ date › | `dateBar(iso, onChange)` |
| Bo'sh holat | `.card > .empty` | — |
| Izoh / formula | `.hint` | — |
| Xato blok | `.err` | render() avtomatik |
| Tasdiq | bottom sheet | `confirmSheet(title, bodyHtml, onOk)` |
| Forma (1–4 maydon) | bottom sheet + `.field` | `sheet(innerHtml, (el, close) => {...})` |
| Qisqa natija | toast 2.2s | `toast(text)` |
| Status yorlig'i | `.pill.good|warn|bad|muted` | — |

Yangi vizual blok kerak deb hisoblasang — avval shu jadvaldan ikkitasini birlashtirib ko'r. Yangi CSS class faqat 3+ joyda takrorlanadigan naqsh uchun.

## 4. Forma va sheet qoidalari

- Sheet ichida: `<h2>` sarlavha → `.hint` kontekst (joriy qiymatlar) → `.field` lar → `.btn.main` → `.btn.ghost` «Бекор». Xavfli amal `.btn.danger`.
- Summa inputi: `type="number" inputmode="numeric"`, yorliqda birlik va limit: `Нақд (сўм, макс 1 200 000)`.
- Validatsiya toast bilan, sheet yopilmaydi: `toast('Суммани киритинг'); return;`. Sabab maydoni ≥ 3 belgi.
- Muvaffaqiyat: `haptic('medium'); close(); toast('✅ ...'); render();` — shu tartibda.
- Tasdiq matni faktlarni qaytaradi: summa, kassa, sana, kim (`<b>${fmt(a)}</b> сўм · ${esc(k.name)} · ${dShort(date)}`).
- Serverdan kelgan har matn `esc()` orqali. Raqam `fmt()` (so'm, butun), `fmtT()` (tiyin → so'm, 2 kasrgacha), `sign()` (+/−), sana `dShort` (DD.MM) yoki `dUz` (12 сентябр). Manfiy belgi uchun «−» (U+2212), «-» emas.

## 5. API chaqiruv qoidalari

- `api(path)` GET, `post(path, body)`, `put(path, body)`. Yo'l `/api` prefiksisiz yoziladi.
- Bitta sahifa = imkon qadar bitta so'rov; backend'da agregat endpoint qilinadi (`/admin/dashboard`, `/admin/kassa/{id}`), frontda 3–4 so'rovni birlashtirish yo'q.
- 401/403 matnlari `api()` ichida markazlashgan, sahifada takrorlanmaydi.
- Uzoq amal (Excel, rasm): darhol `toast('📤 Тайёрланмоқда…')`, natija Telegram chatga ketadi — Mini App kutib turmaydi.

## 6. Ruxsat va rol

- `ME.role` ∈ `BUXGALTER | SUPERADMIN | KASSIR`. Faqat SuperAdmin amallari `${ME?.role === 'SUPERADMIN' ? '<button ...>' : ''}` shaklida yashiriladi, `disabled` qilinmaydi.
- Backend ham tekshiradi; frontdagi yashirish UX uchun, xavfsizlik uchun emas.

## 7. Tekshiruv ro'yxati (PR oldidan)

- [ ] Bo'sh holat, xato holat, yuklanish (skeleton) uchalasi ko'rinadi.
- [ ] Hamma raqam `.num`, hamma server matni `esc()`.
- [ ] `data-go` + `bindGo()`, `CRUMB` yangilandi.
- [ ] Sheet'da Бекор tugmasi bor, fon bosilganda yopiladi.
- [ ] `?v=N` index.html da oshirildi.
- [ ] Qorong'i rejimda ko'rildi.
