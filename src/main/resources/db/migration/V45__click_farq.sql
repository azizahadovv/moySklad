-- ⚠️ Karta farqi nazorati (2026-09-22, docs/JARIMA.md §KARTA-FARQ): karta qoldig'i (haqiqiy) va MoySklad qoldig'i
-- farqi epizodi. farq_tiyin > 0 — MoySklad KO'P (kartadan xarajat qilinib xabar berilmagan → karta mas'uli, muddatda
-- tuzatilmasa ⚖️ jarima); farq_tiyin < 0 — karta KO'P (MoySklad'ga otgruzka/to'lov kiritilmagan → otdel, jarima yo'q).
-- Epizod faqat YANGI karta qoldig'i kelganda ochiladi (eski qoldiq bilan solishtirib yolg'on farq chiqmasin);
-- farq 0 bo'lgan zahoti yopiladi. farq_eval_at — oxirgi baholangan qoldiq vaqti (card_balance_at).
ALTER TABLE click_accounts ADD COLUMN IF NOT EXISTS farq_tiyin       BIGINT NOT NULL DEFAULT 0;
ALTER TABLE click_accounts ADD COLUMN IF NOT EXISTS farq_since       TIMESTAMPTZ;
ALTER TABLE click_accounts ADD COLUMN IF NOT EXISTS farq_notified_at TIMESTAMPTZ;
ALTER TABLE click_accounts ADD COLUMN IF NOT EXISTS farq_jarima_at   TIMESTAMPTZ;
ALTER TABLE click_accounts ADD COLUMN IF NOT EXISTS farq_eval_at     TIMESTAMPTZ;
