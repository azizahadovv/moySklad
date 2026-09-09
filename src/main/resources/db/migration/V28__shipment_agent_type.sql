-- Qarzdorlar ro'yxatida kontragent turi bo'yicha filtr (Юр. лицо / ИП / Физ. лицо).
-- MoySklad companyType: legal | entrepreneur | individual. Bo'sh — hali qayta o'qilmagan (balanceTick to'ldiradi).
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS agent_type VARCHAR(20) NOT NULL DEFAULT '';
