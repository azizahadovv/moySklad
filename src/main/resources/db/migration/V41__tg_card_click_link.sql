-- 💳↔📲 tg_card (HUMOcardbot'dan avtomat o'qilgan karta) endi bevosita click_accounts'ga bog'lanadi.
-- Maqsad (2026-09-15): "Клик" hisobotidagi (Jobs.clickReportNow/cardBlock) MoySklad-vs-karta solishtiruvi
-- uchun "карта қолдиғи" endi QO'LDA (/karta, OCR) emas, balki HUMOcardbot xabaridan AVTOMAT to'ldiriladi
-- (TgReaderService.updateCard() → ClickAccount.cardBalance). Faqat OXIRGI QOLDIQ ko'chiriladi (har bir
-- kirim/chiqim emas) — xuddi qo'lda kiritishning o'rnini bosadi, hisobot va solishtiruv mantig'i o'zgarmaydi.
ALTER TABLE tg_card ADD COLUMN IF NOT EXISTS click_account_id BIGINT REFERENCES click_accounts(id);
