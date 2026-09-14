---
name: miniapp-design-system
description: Касса Назорати Mini App (src/main/resources/static) dizayn tizimi «Ledger» — rang tokenlari, tipografika, holat ranglari, light/dark/Telegram tema qoidalari. app.css yoki index.html ga tegishdan, yangi rang/shrift/komponent stili qo'shishdan OLDIN o'qi.
---

# Mini App dizayn tizimi «Ledger»

Fayllar: `src/main/resources/static/app.css` (195 qator, yagona stil manbai), `index.html` (skelet), `app.js` (UI kodi). Kutubxonasiz, bitta CSS fayl. Yangi CSS fayl yaratilmaydi — hamma stil `app.css` ga qo'shiladi, mavjud bo'lim sarlavhasi (`/* ---------- ... ---------- */`) ostiga.

## 1. Tokenlar — faqat ular ishlatiladi

Hech qayerda hex rang yozilmaydi (istisno: `.sheet-bg` va `.ghost` soyasi). Har rang `var(--...)` orqali.

| Token | Vazifa |
|---|---|
| `--bg` | sahifa foni (qog'oz rang) |
| `--surface` / `--surface-2` | karta foni / ikkilamchi fon (jadval th, zebra, aktiv bosish) |
| `--text` / `--muted` | asosiy matn / ikkilamchi matn, yorliq, izoh |
| `--line` | 1px chiziqlar. Soyalar YO'Q, chegara faqat chiziq |
| `--brand` / `--brand-text` | yuqori bren polosasi va toast foni |
| `--accent` / `--accent-soft` / `--accent-text` | asosiy tugma, aktiv tab, fokus halqasi, KPI ustki chizig'i |
| `--link` | havolalar |
| `--good` / `--warn` / `--bad` + `-soft` juftliklari | holat ranglari (pastga qarang) |
| `--radius` | 6px. Sheet uchun 12px, kichik elementlar 3–4px |
| `--sans` Manrope, `--mono` JetBrains Mono | matn / raqamlar |

Yangi token kerak bo'lsa: 4 ta blokka ham qo'shiladi (`:root`, `@media dark :root:not([data-theme="light"])`, `:root[data-theme="dark"]`, `:root[data-theme="light"]`). Bitta blokda qolgan token — xato.

## 2. Holat semantikasi (uchta rang, uch ma'no)

- `good` (yashil) — mos, tasdiqlangan, topshirilgan. Belgi ✅.
- `warn` (sariq) — farq bor, qisman, e'tibor kerak. Belgi ⚠️ yoki 🟡.
- `bad` (qizil) — muammo, kiritilmagan, kutilayotgan qaror, rad. Belgi ❗ / ❌ / ⛔.
- Neytral — `--line` markeri, `.pill.muted`.

Holat ko'rsatish usullari: `.row.ok|warn|bad` (chapdagi 8px kvadrat marker), `.kpi .v.good|warn|bad` (raqam rangi), `.pill.good|warn|bad`, `.tile.warn|bad` (chegara rangi). Holat hech qachon faqat rang bilan berilmaydi — yonida so'z yoki emoji bo'ladi (`✅ мос`, `фарқ +12 000`).

Mavjud xarita `app.js`: `holatCls(h)` → `teng→ok, farq→warn, kiritilmagan→bad`; `HOLAT` obyekti matnlari. Yangi holat qo'shilsa shu ikki joyga.

## 3. Tipografika

- Body: Manrope 500, 14.5px/1.45. Sarlavhalar 800, letter-spacing −.015em.
- Raqamlar, summalar, sanalar, ID lar — har doim `.num` yoki `.mono` (tabular-nums). Jadvalda `td.num`/`th.num` o'ngga tekislanadi.
- Yorliqlar (`.label`, `.kpi .l`, `th`, `.crumbs`) — 10.5–11px, UPPERCASE, letter-spacing .08–.1em, `--muted`, 700.
- Ikonka sifatida emoji ishlatiladi, SVG ikonka to'plami yo'q. Emoji o'lchami tile'da 20px.

## 4. Yorug'/qorong'i/Telegram tema

- Uch rejim: `auto` (Telegram `colorScheme` ga ergashadi), `light`, `dark`. `localStorage['kn.theme']`, `applyTheme()` in app.js. `data-theme` atributi root'ga qo'yiladi.
- Telegram sarlavha/fon rangi `tg.setHeaderColor / setBackgroundColor` bilan moslashadi. Palitra o'zi Telegram `themeParams` dan olinmaydi — bren ranglari o'zimizniki (qaror: brend izchilligi). Agar Telegram ranglariga to'liq moslash so'ralsa, `--bg`/`--surface`/`--text` ni `themeParams.bg_color/secondary_bg_color/text_color` bilan `auto` rejimda override qilish mumkin, `--accent`/`--brand` qoladi.
- Har yangi komponent ikki rejimda tekshiriladi. Kontrast: `--muted` matn `--surface` ustida ≥ 4.5:1 saqlanadi (hozirgi qiymatlar tekshirilgan).

## 5. Layout qoidalari

- Mobil-first, bitta ustun. Gridlar faqat `1fr 1fr` (`.kpis`, `.tiles`). Ekran kengligi ≥ 400px deb hisoblanadi, lekin 360 da ham buzilmasin.
- Yon bo'shliq 16px (`main`, `.head`). Bloklar orasi 14px (`main gap`). Karta ichi 12×14px.
- Sticky: `.brand` top 0 (z 6), `.nav` top 52px (z 5), `.sheet-bg` z 20, `.toast` z 30, `.ghost` z 40. Yangi qavat qo'shilsa shu tartibga.
- Gorizontal skroll faqat `.tablewrap` va `.seg` ichida. Sahifa o'zi gorizontal skroll qilmaydi.
- `env(safe-area-inset-bottom)` sheet pastida hisobga olinadi.

## 6. Harakat va teginish

- Bosiladigan har element `:active` da `--surface-2` fonga o'tadi (`.row.tap`, `.tile`, `.kpi.link`).
- Bosishda `haptic()` chaqiriladi (yengil), muvaffaqiyatli amalda `haptic('medium')`.
- Animatsiya faqat skeleton (`.skel`), `prefers-reduced-motion` da o'chadi. Boshqa animatsiya qo'shilmaydi.
- Fokus halqasi: `outline: 2px solid var(--accent)` — o'chirilmaydi.

## 7. Nima qilinmaydi

- Soya (`box-shadow`) — faqat drag ghost'da. Gradient — faqat skeleton'da.
- Yangi shrift, yangi radius qiymati, hex rang, `!important` (istisno `tr.total`).
- Inline `style=""` — faqat bir martalik rang (`color:var(--warn)`) uchun; qayta ishlatilsa class'ga aylantiriladi.
- Tailwind/Bootstrap/ikonka kutubxonasi ulanmaydi — index.html'da faqat Google Fonts va o'zimizdagi `telegram-web-app.js`.

## 8. Ish tartibi

1. Yangi ko'rinish kerak bo'lsa avval `[[miniapp-component]]` dagi tayyor bloklardan yig'ib ko'r.
2. Yangi CSS class kerak bo'lsa: mavjud bo'limga qo'sh, token ishlat, 2 rejimda tekshir.
3. `index.html` va `app.js` dagi `?v=N` cache versiyasini birga oshir (hozir `v=14`).
4. `docs/WEB-ADMIN.md` ga yangi ekran qisqacha yoziladi.
