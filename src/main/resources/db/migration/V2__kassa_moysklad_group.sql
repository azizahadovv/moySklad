-- MoySklad otdel (group) -> kassa bog'lanishi.
-- Приходный/Расходный ордер hujjatlari otdel bo'yicha tegishli kassaga yoziladi.
ALTER TABLE kassa ADD COLUMN IF NOT EXISTS moysklad_group_id VARCHAR(64);
