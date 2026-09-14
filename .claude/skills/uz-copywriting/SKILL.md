---
name: uz-copywriting
description: Касса Назорати interfeys matnlari uchun til qoidalari — kirill/lotin qaysi qatlamda, atamalar lug'ati (kassa, tushum, topshirish, qabul, farq, kontragent, qoralama), fe'l shakllari, xato va tasdiq ohangi, ruscha qarz so'zlar. Bot xabari, tugma nomi, Mini App matni, toast yoki hujjat yozishdan OLDIN o'qi.
---

# O'zbek interfeys matni qoidalari

Auditoriya: kassir, buxgalter, direktor (SuperAdmin). Ular Telegram'ni telefonida o'qiydi, matn qisqa va bir ma'noli bo'lishi kerak. Shakl uchun `[[tg-message-format]]`, tugmalar `[[tg-keyboard-style]]`, Mini App `[[miniapp-component]]`.

## 1. Yozuv (kirill / lotin) — qatlam bo'yicha

| Qatlam | Yozuv | Misol |
|---|---|---|
| Mini App (hamma matn) | kirill | `Кутилаётган`, `Пул қабул қилиш` |
| Bot menyu / bo'lim nomlari | kirill (mavjud) | `🏪 Кассалар`, `🏬 Омбор` |
| Bot inline amal tugmalari | lotin | `⬅️ Orqaga`, `✅ Ha, saqlansin` |
| Bot dialog xabarlari, so'rovlar, xatolar | lotin | `Summani kiriting:`, `⚠️ Topilmadi` |
| Avtomatik hisobotlar / notify shablonlar | kirill | `✅ тенг`, `⚠️ фарқ` |
| Kod izohlari, docs/*.md | lotin | — |

Qoida: **bitta xabar/ekran ichida yozuv aralashmaydi**. Mavjud kanonik tugma nomlarining yozuvi o'zgartirilmaydi (admin sozlamalari buziladi). Yangi matn — o'z qatlamining yozuvida.

Lotin yozuvda apostrof: `o'`, `g'` uchun to'g'ri `'` (U+0027) ishlatiladi, kodda mavjud shakl shu. `ʻ`/`’` aralashtirilmaydi (qidiruv va `equals` sinadi).

## 2. Atamalar lug'ati (bir tushuncha — bir so'z)

| Tushuncha | Lotin | Kirill | Ishlatilmaydi |
|---|---|---|---|
| savdo nuqtasi kassasi | kassa | касса | do'kon (faqat ombor kontekstida `Дўкон`) |
| kunlik savdo summasi | tushum | тушум | daromad, savdo summasi |
| kassir pulni buxgalteriyaga beradi | topshirish | топшириш | berish, o'tkazish |
| buxgalter pulni oladi | qabul qilish | қабул қилиш | olish, tasdiqlash |
| MoySklad va bot orasidagi farq | farq | фарқ | xato, nomuvofiqlik (faqat ogohlantirishda `nomuvofiqlik`) |
| naqd / click / terminal | naqd, click, terminal | нақд, click, терминал | plastik, karta (karta = click kartasi obyekti) |
| xaridor/yetkazuvchi | kontragent | контрагент | mijoz, klient |
| qarz (kontragent) | qarz | қарз | debitor |
| ombor buyurtma loyihasi | qoralama | қоралама | draft, loyiha |
| yetkazuvchi | yetkazuvchi | етказувчи | postavshik |
| ombor kamomad | kamchilik | камчилик | defitsit |
| kassir hisobot yuboradi | hisobot topshirish | ҳисобот топшириш | report |
| kutilayotgan qaror | kutilayotgan | кутилаётган | pending |
| xodim | xodim | ходим | ishchi, user (kod: `AppUser`) |
| sozlamalar | sozlamalar | созламалар | nastroyka (istisno: mavjud `⚙️ Настройка` kanonik nom) |
| bo'lim ichidagi orqaga | orqaga | орқага | qaytish, nazad |

Ruscha qarz so'zlar **o'zgartirilmaydi**, chunki MoySklad va buxgalteriya shunday ishlaydi: `приход`, `возврат`, `расход`, `корректировка`, `История`, `Диагностика`. Inglizcha: `click` (kichik), `Excel`, `MoySklad`, `Telegram`, `SuperAdmin`, `Ledger` — shu yozuvda.

## 3. Fe'l va ohang

- Foydalanuvchiga buyruq: **-ing** hurmat shakli, ikki nuqta: `Summani kiriting:`, `Sababni yozing:`. `Kirit`, `yoz` yo'q.
- Tugmadagi tasdiq: majhul nisbat, natija: `Ha, o'chirilsin`, `Ha, saqlansin`. `O'chir`, `Saqla` yo'q.
- Natija xabari: o'tgan zamon, majhul: `✅ Saqlandi`, `✅ Qabul qilindi`, `❌ Bekor qilindi`.
- Jarayon: `-moqda`: `⏳ Yangilanmoqda`, `📤 Chatga yuborilmoqda…` (uch nuqta bitta belgi `…`).
- Xato: bayon + sabab + nima qilish, undovsiz, ayblovsiz: `⚠️ Summa hisobotdan oshmasin` ✓, `Xato kiritdingiz!` ✗. Tizim aybi bo'lsa aytiladi: `⚠️ MoySklad o'qilmadi — bot qiymati ko'rsatildi`.
- Tasdiq so'rovi faktlarni qaytaradi va bitta savol: `<b>150 000</b> so'm · Kassa 3 · 12.09 · topshirdi: Aziz` + `Tasdiqlaysizmi?`. `Rostdan ham?`, `Ishonchingiz komilmi?` yo'q.
- «Siz» shakli ochiq yozilmaydi (`Sizning kassangiz` ✗ → `Kassangiz`).

## 4. Qisqalik

- Tugma ≤ 20 belgi, toast ≤ 40, so'rov xabari ≤ 2 qator, izoh (`.hint`, `<i>`) ≤ 1 jumla.
- Bir jumlada bitta fikr. Bo'sh holat matni aniq va tinch: `Ҳамма кунлар топширилган.`, `Амаллар йўқ.` — `Ma'lumot topilmadi` kabi umumiy shakl yo'q.
- Raqam + birlik: `3 та`, `5 кун`, `1 200 000 сўм`; birlik hech qachon tashlab ketilmaydi.
- Sana so'z bilan faqat sarlavhada (`12 сентябр`), jadval/ro'yxatda `12.09`.

## 5. Hujjat (docs/*.md) uslubi

- Lotin o'zbek, texnik atamalar inglizcha asl yozuvda. Har hujjat boshida 3–5 qator «nima uchun», keyin qoidalar raqamlangan. Qarorlar sanasi bilan (`2026-09-12: ...`).
- Foydalanuvchi ko'radigan matn hujjatda **aynan** interfeysdagidek keltiriladi (yozuv va emoji bilan), tarjima qilinmaydi.

## 6. Tekshiruv

Yangi matn yozilganda: qatlam yozuvi to'g'rimi → lug'atdagi so'zmi → fe'l shakli (-ing / -ilsin / -ildi / -moqda) → prefiks bitta → uzunlik limiti → birlik bor.
