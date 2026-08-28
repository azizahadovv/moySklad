-- Qo'lda qo'yilgan nom himoyasi: name_locked=true bo'lsa «🔄 Номлар (MoySklad)»
-- yangilashi bu yozuvning nomiga TEGMAYDI (otdel/hisob bog'lanishi saqlanadi).
ALTER TABLE kassa ADD COLUMN name_locked BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE click_accounts ADD COLUMN name_locked BOOLEAN NOT NULL DEFAULT false;

-- «Клик Самойиддин» user tomonidan atalgan nom — MoySklad yangilashi o'chirmasin
UPDATE click_accounts SET name_locked = true WHERE name = 'Клик Самойиддин';
