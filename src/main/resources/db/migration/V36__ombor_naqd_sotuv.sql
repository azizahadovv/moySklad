-- 🏬 Омбор: sotuv tahlili faqat NAQD statusdagi otgruzkalardan (2026-09-14, user qarori).
-- Sabab: report/profit/byproduct statusni bilmaydi — Перечисление / Карз перечисление (bank, tashkilotlar)
-- va bitta katta hujjat (1,48 mlrd) chempion, ABC, buyurtma nuqtasi va sanoq havzasini buzayotgan edi.
-- Endi: demand hujjatlari pozitsiyasi bilan sinxron qilinadi, SOTUV_* = naqd statuslar (ombor.naqd_statuslar)
-- va hujjat summasi chegaradan (ombor.sotuv_max_hujjat) oshmaganlar; MoySklad hisoboti JAMI_* sifatida qoladi
-- (tannarx manbai va taqqoslash uchun).

-- Chegirma (%): naqd narx = price × (1 − discount/100)
ALTER TABLE ombor_pozitsiya ADD COLUMN IF NOT EXISTS discount NUMERIC(8,3) NOT NULL DEFAULT 0;

-- MoySklad hisoboti (barcha statuslar) → JAMI_* kodlariga ko'chadi; SOTUV_* endi naqd uchun qayta hisoblanadi
UPDATE ombor_korsatkich SET code = 'JAMI_MIQDOR'           WHERE code = 'SOTUV_MIQDOR';
UPDATE ombor_korsatkich SET code = 'JAMI_SUMMA'            WHERE code = 'SOTUV_SUMMA';
UPDATE ombor_korsatkich SET code = 'JAMI_TANNARX'          WHERE code = 'TANNARX_SUMMA';
UPDATE ombor_korsatkich SET code = 'JAMI_FOYDA'            WHERE code = 'FOYDA';
UPDATE ombor_korsatkich SET code = 'JAMI_QAYTARISH_MIQDOR' WHERE code = 'QAYTARISH_MIQDOR';
UPDATE ombor_korsatkich SET code = 'JAMI_QAYTARISH_SUMMA'  WHERE code = 'QAYTARISH_SUMMA';

-- Tunlik hisoblar (ORTACHA, ABC, BUYURTMA_NUQTA, FILL) naqd SOTUV_* kelganda qayta yoziladi
DELETE FROM settings WHERE key = 'ombor.naqd_rebuilt';
