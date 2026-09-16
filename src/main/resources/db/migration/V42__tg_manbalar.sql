-- 📨 tg-reader kengaytmasi (2026-09-16): bir nechta manba (bot / guruh / kanal / foydalanuvchi / «Saqlangan xabarlar»),
-- manba kesimida backfill holati, 🔐 xavfsizlik (faol seanslar, 2FA).

-- Xabar kaliti endi MANBA bilan: kanal/guruhda msg_id har chatda o'zicha 1 dan boshlanadi — (phone, msg_id) yetarli emas.
-- Eski UNIQUE (phone, msg_id) nomi bilan emas, ta'rifi bilan topib o'chiriladi (nom serverga qarab farq qilishi mumkin).
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'tg_xabar'::regclass AND contype = 'u' AND pg_get_constraintdef(oid) = 'UNIQUE (phone, msg_id)';
    IF c IS NOT NULL THEN EXECUTE format('ALTER TABLE tg_xabar DROP CONSTRAINT %I', c); END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS ux_tg_xabar_src ON tg_xabar(phone, source_bot, msg_id);

-- Manba kesimida oxirgi o'qilgan xabar (tg_akkaunt.last_msg_id — asosiy bot uchun eski maydon, mos holda yangilanadi)
CREATE TABLE IF NOT EXISTS tg_manba_holat (
    phone        VARCHAR(24) NOT NULL,
    source       VARCHAR(64) NOT NULL,               -- bot/odam username (@siz), «me», yoki chat id (-100…)
    last_msg_id  BIGINT      NOT NULL DEFAULT 0,
    last_msg_at  TIMESTAMP,
    PRIMARY KEY (phone, source)
);

-- 🔐 Xavfsizlik: 2FA bor-yo'qligi, faol seanslar (JSON, account.getAuthorizations), tekshirilgan vaqt
ALTER TABLE tg_akkaunt ADD COLUMN IF NOT EXISTS two_fa        BOOLEAN;
ALTER TABLE tg_akkaunt ADD COLUMN IF NOT EXISTS sessions_json TEXT;
ALTER TABLE tg_akkaunt ADD COLUMN IF NOT EXISTS sessions_at   TIMESTAMP;

-- Sozlamalar (settings, kod default beradi): tgreader.sources (CSV), tgreader.media_ocr (0/1),
-- tgreader.balance_cmd («|» bilan qadamlar), tgreader.balance_before_min, tgreader.security_at.
