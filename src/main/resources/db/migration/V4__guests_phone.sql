-- Mehmon telefon raqami — admin foydalanuvchini ID emas, raqam orqali topadi.
ALTER TABLE guests ADD COLUMN IF NOT EXISTS phone VARCHAR(32);
