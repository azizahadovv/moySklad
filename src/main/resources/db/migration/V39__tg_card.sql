-- 📨 Bot xabarlari → 💳 karta qoldiqlari (2026-09-14): HUMOCARD kabi botlar har kirim/rasxodda pastda balansni
-- ko'rsatadi. Bot shu balansni avtomat qabul qilib boradi (skrinshot o'rniga) va guruhga hisobot qiladi.

-- Xabardan ajratilgan maydonlar
ALTER TABLE tg_xabar ADD COLUMN IF NOT EXISTS dir       VARCHAR(8);       -- KIRIM | RASXOD
ALTER TABLE tg_xabar ADD COLUMN IF NOT EXISTS balance   BIGINT;           -- pastdagi qoldiq (tiyin)
ALTER TABLE tg_xabar ADD COLUMN IF NOT EXISTS card_mask VARCHAR(8);       -- 1090
ALTER TABLE tg_xabar ADD COLUMN IF NOT EXISTS merchant  VARCHAR(200);     -- MULTICARD MUNIS>TOSH
ALTER TABLE tg_xabar ALTER COLUMN amount TYPE BIGINT;   -- tiyin (V38 so'm edi; parser endi tiyin yozadi)

-- Karta qoldig'i (mask kesimida, oxirgi holat)
CREATE TABLE IF NOT EXISTS tg_card (
    id              BIGSERIAL    PRIMARY KEY,
    source_bot      VARCHAR(64)  NOT NULL DEFAULT '',
    mask            VARCHAR(8)   NOT NULL,
    name            VARCHAR(64)  NOT NULL DEFAULT '',   -- HUMOCARD
    phone           VARCHAR(24),                        -- oxirgi xabar qaysi akkauntdan
    kassa_id        BIGINT,                             -- ixtiyoriy bog'lanish (do'kon/otdel)
    currency        VARCHAR(8)   NOT NULL DEFAULT 'UZS',
    balance         BIGINT       NOT NULL DEFAULT 0,    -- tiyin
    last_dir        VARCHAR(8),
    last_amount     BIGINT,
    last_merchant   VARCHAR(200) NOT NULL DEFAULT '',
    last_txn_at     TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (source_bot, mask)
);
CREATE INDEX IF NOT EXISTS idx_tg_xabar_card ON tg_xabar(card_mask, msg_at DESC);

-- Hisobot sozlamalari (Click hisoboti uslubida)
-- tgreader.report_every_h / report_from / report_to / report_sent — settings jadvalida (kod default beradi).
