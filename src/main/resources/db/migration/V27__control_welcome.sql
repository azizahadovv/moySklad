-- 🕵️ Назорат: xodim Telegram'ga ulanganda unga ochiq kontragent xatolari va otgruzka kamchiliklari
-- BIR MARTA yuboriladi (ungacha bu xabarlar «xodim ulanmagan» belgisi bilan SuperAdmin'ga ketgan edi).
-- NULL — hali yuborilmagan. Allaqachon ulanganlarga eski xabarlar qayta yuborilmaydi.
ALTER TABLE users ADD COLUMN IF NOT EXISTS control_welcome_at TIMESTAMPTZ;
UPDATE users SET control_welcome_at = now() WHERE telegram_id IS NOT NULL;
