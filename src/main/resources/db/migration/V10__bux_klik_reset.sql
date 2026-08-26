-- ============================================================
-- KLIK siyosati o'zgardi (2026-08): klik puli buxgalteriyaga
-- O'TKAZILMAYDI — har bir kassa o'z klik hisobini o'zi jamlaydi.
-- Buxgalteriyada shu paytgacha yig'ilib qolgan KLIK balansi
-- KORREKTIROVKA operatsiyasi bilan 0 ga tushiriladi (tarix jurnalda qoladi).
-- ============================================================

-- Musbat qoldiq: buxgalteriyadan ayirilgani sifatida yoziladi
INSERT INTO operations (type, money_type, amount, from_owner_type, from_owner_id,
                        status, comment, op_date)
SELECT 'KORREKTIROVKA', 'KLIK', b.amount, 'BUXGALTERIYA', b.owner_id,
       'TASDIQLANGAN',
       'Klik siyosati: buxgalteriya Klik balansi 0 ga tushirildi (klik endi har bir kassaning o''z hisobida yuritiladi)',
       CURRENT_DATE
FROM balances b
WHERE b.owner_type = 'BUXGALTERIYA' AND b.money_type = 'KLIK' AND b.amount > 0;

-- Manfiy qoldiq: buxgalteriyaga qo'shilgani sifatida yoziladi
INSERT INTO operations (type, money_type, amount, to_owner_type, to_owner_id,
                        status, comment, op_date)
SELECT 'KORREKTIROVKA', 'KLIK', -b.amount, 'BUXGALTERIYA', b.owner_id,
       'TASDIQLANGAN',
       'Klik siyosati: buxgalteriya Klik balansi 0 ga tushirildi (klik endi har bir kassaning o''z hisobida yuritiladi)',
       CURRENT_DATE
FROM balances b
WHERE b.owner_type = 'BUXGALTERIYA' AND b.money_type = 'KLIK' AND b.amount < 0;

UPDATE balances
SET amount = 0, updated_at = now()
WHERE owner_type = 'BUXGALTERIYA' AND money_type = 'KLIK' AND amount <> 0;
