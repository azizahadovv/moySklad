-- 🏬 Омбор: partiya tahlili (kelish → tugash) uchun qoldiq harakati hujjatlari bir yillik tarix bilan (2026-09-14).
-- Kursor o'chirilsa OmborDocSync birinchi yuklash rejimida sales_days (365) kun, 30 kunlik bo'laklarda qayta o'qiydi
-- (demand allaqachon 365 kun). O'tkazilmagan hujjatlar qoidasi docs_days oynasi bilan cheklandi.
DELETE FROM ombor_sinxron WHERE entity IN ('supply', 'move', 'salesreturn', 'retailsalesreturn', 'loss', 'enter', 'purchasereturn');
CREATE INDEX IF NOT EXISTS idx_ombor_hujjat_target ON ombor_hujjat(target_kassa_id) WHERE target_kassa_id IS NOT NULL;
