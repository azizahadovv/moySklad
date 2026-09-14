-- 📨 Bot xabarlari nazorati (2026-09-14): akkauntga MTProto (tg-reader servisi) orqali kirib, bitta botdan
-- (@HUMOcardbot) kelgan xabarlarni o'qish, tekshirish, rahbarga/guruhga xabar berish.

-- Ulangan akkauntlar (tg-reader har akkaunt uchun bitta sessiya)
CREATE TABLE IF NOT EXISTS tg_akkaunt (
    phone         VARCHAR(24)  PRIMARY KEY,          -- +998...
    name          VARCHAR(200) NOT NULL DEFAULT '',
    tg_user_id    BIGINT,
    username      VARCHAR(64)  NOT NULL DEFAULT '',
    source_bot    VARCHAR(64)  NOT NULL DEFAULT '',   -- qaysi bot o'qilyapti
    user_id       BIGINT,                             -- botdagi xodim (users.id), qo'lda bog'lanadi
    kassa_id      BIGINT,                             -- ixtiyoriy: do'kon
    last_msg_id   BIGINT       NOT NULL DEFAULT 0,    -- oxirgi o'qilgan xabar (backfill uchun)
    last_seen_at  TIMESTAMP,                          -- oxirgi heartbeat (jimlik nazorati)
    last_msg_at   TIMESTAMP,                          -- oxirgi xabar vaqti
    last_error    VARCHAR(300),
    active        BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- Xabarlar jurnali
CREATE TABLE IF NOT EXISTS tg_xabar (
    id            BIGSERIAL    PRIMARY KEY,
    phone         VARCHAR(24)  NOT NULL,
    source_bot    VARCHAR(64)  NOT NULL DEFAULT '',
    msg_id        BIGINT       NOT NULL,
    msg_at        TIMESTAMP,                          -- xabar vaqti (Telegram, UTC → saqlanadi as-is)
    text          TEXT         NOT NULL DEFAULT '',
    media         VARCHAR(16)  NOT NULL DEFAULT '',   -- '', photo, document
    amount        BIGINT,                             -- matndan ajratilgan summa (so'm), yo'q bo'lsa NULL
    verdict       VARCHAR(16)  NOT NULL DEFAULT 'YANGI',  -- YANGI, OK, OGOH, MOS, NOMOS
    note          VARCHAR(400) NOT NULL DEFAULT '',   -- tekshiruv izohi
    notified_at   TIMESTAMP,                          -- ogohlantirish yuborilgan vaqt
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (phone, msg_id)
);
CREATE INDEX IF NOT EXISTS idx_tg_xabar_at ON tg_xabar(msg_at DESC);
CREATE INDEX IF NOT EXISTS idx_tg_xabar_verdict ON tg_xabar(verdict, msg_at DESC);
