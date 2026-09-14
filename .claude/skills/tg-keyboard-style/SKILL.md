---
name: tg-keyboard-style
description: Telegram bot tugmalari (ReplyKeyboard/Inline) qoidalari — emoji+matn shakli, qator tartibi, Orqaga/Bekor/Ha-Yo'q standart nomlari, callback_data formati va prefikslar, Keyboards.java yordamchilari, LabelService kanonik nom qoidasi. Handler'ga yangi tugma yoki klaviatura qo'shishdan OLDIN o'qi.
---

# Bot klaviatura qoidalari

Manba: `uz.kassa.bot.Keyboards` (yordamchilar), `LabelService` (qayta nomlash), `MenuSchemaService` (tartib/ustunlar), `Router.onCallback` (callback yo'naltirish). Xabar matni uchun `[[tg-message-format]]`, so'z tanlash uchun `[[uz-copywriting]]`.

## 1. Tugma nomi shakli

- `<emoji> <matn>`. Emoji oldinda, bitta. Istisno: sahifalash `Keyingi ➡️` (emoji oxirida, yo'nalishni bildiradi).
- Uzunlik 5–20 belgi, qattiq limit 40 (admin shablon tugmalari ham). Bir qatorda 2 tugma bo'lganda har biri ≤ 16 belgi, aks holda telefon ekranida qirqiladi.
- Til qatlami (hozirgi holat, o'zgartirilmaydi):
  - **Menyu / bo'lim nomlari** (reply klaviatura, panel ildizlari) — kirill: `🏪 Кассалар`, `📥 Кутилаётганлар`, `🏬 Омбор`.
  - **Amal / dialog tugmalari** (inline) — lotin: `⬅️ Orqaga`, `❌ Bekor`, `✅ Ha, o'chirilsin`.
  - Yangi bo'lim qo'shilsa shu qatlamga mos yoziladi. Mavjud nomning yozuvini almashtirish taqiqlanadi (§5).

## 2. Standart tugmalar — faqat shu variantlar

| Vazifa | Nom | Izoh |
|---|---|---|
| Bir qadam orqaga (umumiy) | `⬅️ Orqaga` | reply va inline uchun bir xil |
| Orqaga, manzil aniq | `⬅️ <Manzil>` | masalan `⬅️ Ro'yxat`, `⬅️ Омбор`. Faqat manzil foydalanuvchiga ma'noli bo'lsa; bir oqim ichida bitta usul tanlanadi |
| Bekor (oqimni to'xtatish) | `❌ Bekor` | `Bekor qilish`, `🚫 Bekor qilish` yozilmaydi |
| Tasdiq | `✅ Ha, <fe'l>` | `✅ Ha, o'chirilsin`, `✅ Ha, saqlansin`. Fe'l majhul nisbatda, natijani aytadi |
| Rad | `❌ Yo'q` | `⬅️ Yo'q` yozilmaydi |
| Sahifalash | `⬅️ Oldingi` / `Keyingi ➡️` | bitta qatorda, faqat mavjud yo'nalish ko'rsatiladi |
| Bo'lim ildiziga | `<bo'lim emoji> <Bo'lim>` | orqaga qatorida ikkinchi tugma: `irow(btn("⬅️ Ro'yxat", ..), btn("🏬 Омбор", "om:m"))` |
| Matnli bekor | «`-` yuboring» | faqat matn kiritish bosqichida, inline `❌ Bekor` bilan birga |

Ha/Yo'q qatori: ikkitasi bir qatorda, `✅` chapda. Halokatli amal (o'chirish, nol qilish) har doim shu tasdiq qadamidan o'tadi.

## 3. Qator tartibi

- Standart 2 ustun. `addGrid(rows, labels, cols)` ustunni 1..3 ga cheklaydi; admin `menu.cols.<key>` bilan o'zgartira oladi.
- Inline: asosiy amallar yuqorida (1–2 qator), keyin ikkilamchi, **eng oxirida yakka `⬅️` qatori**. Orqaga qatori hech qachon o'rtada bo'lmaydi.
- Uzun ro'yxat (>8 element) — sahifalash, `PAGE` konstantasi handler'da, nav qatori orqaga qatoridan oldin.
- Reply klaviatura har doim `setResizeKeyboard(true)`. Reply klaviaturada WebApp tugmasi TAQIQLANGAN (initData kelmaydi); Mini App faqat `/start` inline tugmasi yoki `≡` menyu tugmasi orqali.
- Rangli tugmalar `sbtn(text, data, style)` (`PRIMARY/SUCCESS/DANGER`) — hozir faqat Омбор bo'limida. Yangi joyda ishlatilsa: `SUCCESS` = tasdiq, `DANGER` = o'chirish/rad, `PRIMARY` = asosiy yo'l; bir klaviaturada ko'pi bilan 1 ta `DANGER`.

## 4. callback_data

- Format `<prefix>:<cmd>[:<arg>]`, ajratgich `:`, murakkab arg ichida `.` (`kg:dl:<kassa>.<page>.<status>`).
- Prefikslar band: `a:` admin, `kg:` kontragent/nazorat, `om:` ombor, `k:` kassir, `b:` buxgalter, `rol:`, `sb:` submission, `tr:` transfer, `dr:` kunlik hisobot, `kb/ks/ku/kx/kf/km/kq/kv/kp/kc:` karta OCR, `cx` global bekor, `m:<menuKey>` panel navigatsiya. Yangi modul — yangi 2–3 harfli prefiks, `Router.onCallback` ga `startsWith` bilan qo'shiladi.
- `cmd` 2–4 harf (`dl`, `ok`, `no`, `pg`). Telegram limiti 64 bayt, kodda tekshiruv yo'q — shuning uchun ID va sahifa raqamidan boshqa narsa argga solinmaydi; matn hech qachon callback'da yurmaydi (session'da saqlanadi).
- Har handler o'z `BACK` konstantasini e'lon qiladi (`ControlAdminHandler.BACK = "a:ct"`).

## 5. Kanonik nom qoidasi (buzilsa navigatsiya sinadi)

- Kodda tugma har doim **kanonik** satr bilan yoziladi va tekshiriladi. Foydalanuvchi ko'radigan nom `LabelService.display()` orqali, kirgan matn `LabelService.canonical()` orqali qaytariladi. Ko'rinadigan matnni to'g'ridan-to'g'ri `equals` qilish xato.
- Admin nomni `label.<kanonik>` kalitida qayta nomlaydi, `label.off.<kanonik>` yashiradi. Kanonik satr o'zgarsa admin sozlamalari yo'qoladi — shuning uchun mavjud kanonik nomlar (kirill/lotin yozuvi bilan birga) o'zgartirilmaydi.
- Yangi menyu tugmasi qo'shilganda **to'rt joy** yangilanadi: `Keyboards.KASSIR_MAIN/BUX_MAIN` (yoki tegishli bo'lim ro'yxati), `Keyboards.MENU_LABELS`, `LabelService.RENAMABLE`, `MenuSchemaService.MENUS/SUBMENUS`. Bittasi qolib ketsa: tugma ko'rinadi, lekin qayta nomlanmaydi yoki tartiblanmaydi.
- Admin shablon tugmalari (`🔔 Билдиришномалар`) mavjud kanonik nomlar bilan to'qnashmasligi, `/` bilan boshlanmasligi tekshiriladi; ular oxirida, 2 ta qatorda, notify o'chiq bo'lsa yashirin.

## 6. Yordamchilar (Keyboards.java)

`btn(text, data)`, `sbtn(text, data, style)`, `irow(btn...)`, `inline(rows)`, `addGrid(rows, labels, cols)`, `addRowIf(rows, visible, canonicals...)`, `kassirMenu(visible, extra)`, `buxMenu(visible, extra, superadmin, webappUrl)`, `levelMenu(labels, visible)`, `arrange(key, labels)`, `mtChoice(prefix)`, `cancelOnly()`. Panel ichida: `AdminSupport.navTo(...)`, `sendContent(...)` — chatda faqat panel + kontent xabari qoladi, eskisi o'chiriladi.

Yangi `InlineKeyboardMarkup` qo'lda `new` qilinmaydi; `inline(List.of(irow(...), irow(...)))`.

## 7. Ma'lum qarzlar (yangi kodda takrorlanmasin)

- Kassir menyusida lotin/kirill aralash (`🔁 O'tkazma` yonida `📊 КАССАМ`) — tarixiy, kanonik nomlar sababli tegilmaydi.
- `Keyboards.MENU_LABELS` da eskirgan nomlar (`📊 Bugungi holat`, `💰 Balansim`, `👑 АДМИН ПАНЕЛ`) — yangi kod ularga tayanmaydi.
- `kassirMenu()`, `buxMenu(boolean)`, `levelMenu(List)` eski variantlar — yangi kod `Predicate` li variantlarni chaqiradi.
- Chuqur inline oqimdan bosh menyuga bir bosishda chiqish yo'q; hozircha orqaga qatorida bo'lim ildizi tugmasi beriladi.
