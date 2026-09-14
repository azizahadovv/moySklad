---
name: tg-message-format
description: Telegram bot xabar matni qoidalari — HTML parse mode va TextUtil.esc, sarlavha/ajratgich/bullet shakli, pul (fmt/fmtTiyin, so'm) va sana (dd.MM.yyyy, Asia/Tashkent) formati, ⚠️/✅/⏳ status prefikslari, sahifalash va panel xabar naqshi. Botdan foydalanuvchiga ketadigan har qanday matnni yozish yoki o'zgartirishdan OLDIN o'qi.
---

# Bot xabar formati

Manba: `Sender` (parse mode markazda), `TextUtil` (esc, fmt), `Jobs.RULE_TOP/RULE_MID`, `docs/NOTIFY-SHABLON.md`. Tugmalar uchun `[[tg-keyboard-style]]`, so'z tanlash `[[uz-copywriting]]`.

## 1. HTML va escape

- parse_mode har doim `HTML`, faqat `Sender` ichida o'rnatilgan. Handler `setParseMode` chaqirmaydi, Markdown ishlatilmaydi.
- Foydalanuvchi/MoySklad/DB dan kelgan **har bir** satr `esc(...)` (static import `TextUtil.esc`) orqali: kontragent nomi, kassa nomi, izoh, xodim ismi. `&`, `<`, `>` escape qilinadi. Escape qilinmagan nom — xabar umuman yuborilmaydi (Telegram 400).
- Teglar: `<b>` sarlavha va asosiy raqam, `<code>` ID, buyruq, token niqobi, `<i>` izoh/footnote, `<a href>` havola. `<u>`, `<pre>`, `<s>` ishlatilmaydi.

## 2. Xabar skeleti

```
<emoji> <b>SARLAVHA</b>

<asosiy ma'lumot 1–5 qator>
━━━━━━━━━━━━━━━━━━━━
<ikkinchi blok yoki jami>

<i>izoh yoki keyingi qadam</i>
<buyruq / savol>:
```

- Sarlavha: emoji + `<b>`, keyin **bo'sh qator**. Panel ildizi: `"🏪 <b>KASSA</b>\n\nBo'limni tanlang:"`.
- Ajratgichlar: `RULE_TOP` = `━` × 20 (bloklar orasida), `RULE_MID` = `─` × 22 (blok ichida kichik bo'linish). Notify shablonlarida faqat `━`. Yangi ajratgich chizmalari (`===`, `***`) yozilmaydi.
- Bir qatorda maydonlar `·` bilan: `12.09 · Kassa 3 · Aziz`. Sabab/izoh ` — ` bilan: `⚠️ Topilmadi — ID noto'g'ri`.
- Ro'yxat: `• ` (ichki daraja 3 bo'shliq + `• `). Raqamlangan ro'yxat faqat qadamlar ketma-ketligi uchun.
- Buyruq/so'rov xabari ikki nuqta bilan tugaydi va imperativ: `Summani kiriting:`, `Sababni yozing:`, `Bo'limni tanlang:`.
- Xabar 4096 belgidan uzun bo'lmasin; ro'yxat 15+ qator bo'lsa sahifalash yoki Excel.

## 3. Status prefikslari (bitta xabarga bitta)

| Prefiks | Ma'no | Shakl |
|---|---|---|
| `✅` | bajarildi | `✅ Saqlandi:` + natija; fe'l o'tgan zamon, majhul |
| `⚠️` | ogohlantirish, xato, ruxsat yo'q | `⚠️ <holat> — <sabab>.` bayon, undov yo'q. Jiddiy: `⚠️ <b>...</b>` |
| `❗️` | kirish yetishmaydi | `❗️ Summa kiritilmagan` |
| `🚫` | taqiq, o'chirish | `🚫 O'chirildi`, `🚫 Faqat SuperAdmin` |
| `⏳` | kutilmoqda, jarayonda | `⏳ Yangilanmoqda (1–5 daqiqa)` |
| `ℹ️` | ma'lumot | qisqa izoh |
| `❌` | bekor qilindi / rad | `❌ Bekor qilindi` |
| `🔴 / 🟢` | holat markeri ro'yxat ichida | qator boshida |
| `✅ тенг / ⚠️ фарқ / ❗️ киритилмаган` | karta/hisobot holati — qattiq lug'at | o'zgartirilmaydi |

Callback'ga qisqa javob: `Sender.answerAlert(callbackId, text)` — popup, faqat ⚠️ holatlar uchun; muvaffaqiyat xabar sifatida ketadi.

## 4. Pul

- `TextUtil.fmt(long)` → `1 234 567` (bo'shliq ming ajratgich, kasr yo'q). Tiyin: `TextUtil.fmtTiyin(long)` → `12 235.45`, `.00` tashlanadi. Kirish: `parseAmount` / `parseAmountTiyin`.
- Summa har doim `<b>`: `<b>1 234 567</b> so'm`. Valyuta so'zi summadan keyin, bo'shliq bilan.
- Valyuta so'zi xabar tilida: lotin xabarda `so'm`, kirill xabarda `сўм`. `сум` yozilmaydi. Bitta xabarda ikkalasi aralashmaydi.
- Manfiy: `−` (U+2212) yoki `sign()` uslubida `+`/`−` oldinda. Farq: `фарқ +12 000`.
- MoySklad o'qilmasa: bot DB qiymati ko'rsatiladi, xabar oxiriga `⚠️` marker.

## 5. Sana va vaqt

- Zona `props.zoneId()` (`Asia/Tashkent`), scheduler'da `${app.zone:Asia/Tashkent}`. `LocalDate.now()` zonasiz chaqirilmaydi. MoySklad API vaqtlari Moskva (UTC+3) — konvertatsiya `MoySkladHttp.toMs/fromMs`.
- Formatlar: sana `dd.MM.yyyy`, vaqt bilan `dd.MM.yyyy HH:mm`, qisqa `dd.MM HH:mm` (audit, karta), faqat `dd.MM` yoki `HH:mm` jadval ichida. Oy nomi bilan: `12 Sentyabr` (`AdminSupport.OYLAR`, lotin).
- Yangi `DateTimeFormatter` e'lon qilishdan oldin sinfda/`AdminSupport` da mavjudini ishlat (`DF`, `AUDIT_DF`); 12 sinfda takrorlangan, ko'paytirilmasin.

## 6. Sahifalash va panel

- Offset sahifalash, sahifa raqami callback'da, `PAGE` konstanta handler'da. Xabar sarlavhasida `(2/5)` ko'rsatiladi.
- Admin paneli: chatda faqat 2 bot xabari (panel + kontent), eskilari `AdminSupport.deletePrevPanel/sendContent` orqali o'chiriladi; panel ichidagi foydalanuvchi xabari ham o'chiriladi. Yangi panel ekrani shu naqshdan chiqmaydi — har bosishda yangi xabar yuborish taqiqlangan.
- Mavjud hisobot va xabar matnlari **muzlatilgan**: yangi xabar turi `🔔 Билдиришномалар` shablon tizimi (`NotifyPresets`, `docs/NOTIFY-SHABLON.md`) orqali qo'shiladi, `{placeholder}` va `|tiyin |ming |mln |+ |son` formatlari bilan. Noma'lum placeholder saqlashda `⚠️` bilan belgilanadi.

## 7. Nima qilinmaydi

- Escape'siz interpolyatsiya, Markdown, `<pre>` jadval (mobil ekranda buziladi — jadval kerak bo'lsa Excel/rasm).
- Bir xabarda 3+ emoji prefiks, «!!!», CAPS (menyu nomlaridan tashqari).
- Xatoni stack trace yoki HTTP kod bilan ko'rsatish: foydalanuvchiga sabab + nima qilish, texnik detal logga.
