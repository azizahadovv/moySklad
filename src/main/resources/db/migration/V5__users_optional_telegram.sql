-- Kassir jadvaldan (Google Sheets) Telegram'siz ham yaratiladi;
-- telegram_id keyin kontakt yuborilganda telefon orqali bog'lanadi.
ALTER TABLE users ALTER COLUMN telegram_id DROP NOT NULL;
