-- Karta (Click ilovasidagi HAQIQIY) qoldig'i nazorati: MoySklad'da bu raqam yo'q,
-- uni mas'ul xodim /karta buyrug'i bilan kiritadi; soatlik guruh hisobotida
-- MoySklad qoldig'i bilan solishtirilib chiqadi.
ALTER TABLE click_accounts ADD COLUMN card_balance    BIGINT;
ALTER TABLE click_accounts ADD COLUMN card_balance_at TIMESTAMPTZ;
ALTER TABLE click_accounts ADD COLUMN card_balance_by VARCHAR(120);
-- Hisobot matnida murojaat qilinadigan mas'ul (masalan "@Ibodullayev_Ali"
-- yoki "{id=123456;Ism}"). /kartamas buyrug'i bilan o'rnatiladi.
ALTER TABLE click_accounts ADD COLUMN card_responsible VARCHAR(160);
