-- Botga yozgan, lekin hali tizimga qo'shilmagan foydalanuvchilar.
-- SuperAdmin "Foydalanuvchi qo'shish"da shu ro'yxatdan tanlaydi.
CREATE TABLE IF NOT EXISTS guests (
    telegram_id BIGINT PRIMARY KEY,
    name        VARCHAR(160),
    username    VARCHAR(80),
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT now()
);
