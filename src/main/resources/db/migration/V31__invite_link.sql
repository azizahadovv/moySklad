-- 🔗 Taklif (referal) havolasi: Telegram'ga ulanmagan xodimga t.me/<bot>?start=inv_<token> beriladi.
-- Havolani bosib 📱 kontakt yuborsa — tasdiqsiz ulanadi (telefoni bo'lsa mos kelishi shart, bo'lmasa yoziladi).
-- Bir martalik, 24 soat. 💼 job_title — qo'lda kiritiladigan lavozim (faqat ko'rsatish uchun).
ALTER TABLE users ADD COLUMN IF NOT EXISTS invite_token VARCHAR(40);
ALTER TABLE users ADD COLUMN IF NOT EXISTS invite_expires_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS job_title VARCHAR(120);
ALTER TABLE guests ADD COLUMN IF NOT EXISTS invite_token VARCHAR(40);
CREATE UNIQUE INDEX IF NOT EXISTS ux_users_invite_token ON users(invite_token) WHERE invite_token IS NOT NULL;
