-- 💳 tg_card.hidden (2026-09-16): akkauntga ulangan BARCHA kartalar (shu jumladan xodimning shaxsiy kartasi) HUMOcardbot'dan
-- keladi — shaxsiy kartani «💳 Карта қолдиқлари» hisobotidan va Σ dan chiqarib qo'yish uchun belgi
-- (📨 → 💳 Karta qoldiqlari → 🔗 Клик билан боғлаш → karta → 🙈). Xabarlar baribir o'qiladi, faqat hisobotga kirmaydi.
ALTER TABLE tg_card ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT false;
-- 📲 Click hisoboti: karta qoldig'i N soatdan eski bo'lsa «⏰ маълумот янгиланмаган» + mas'ulga eslatma —
-- notify.clickStaleHours (settings, standart 2; ⚙️ → 📣 Гуруҳлар/Каналлар → ⏰ Yuborish vaqtlari).
