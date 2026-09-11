-- 🏬 Омбор B3 (yetkazuvchi, narx) + B4 (so'rov, hamkorlar) + B5 (qoralama va tasdiq zanjiri) — docs/OMBOR-TZ.md §12

-- HAMKOR_INTERVAL kabi "kontragent|tovar" kalitlari uchun kengaytirildi
ALTER TABLE ombor_korsatkich ALTER COLUMN product_ms_id TYPE VARCHAR(90);

-- Yetkazuvchi profili (kontragent): muddatlar, landed koef, ishonch tarixi
CREATE TABLE IF NOT EXISTS ombor_yetkazuvchi (
    agent_ms_id   VARCHAR(40)  PRIMARY KEY,
    name          VARCHAR(300) NOT NULL DEFAULT '',
    country       VARCHAR(40)  NOT NULL DEFAULT '',
    lead_days_am  INTEGER      NOT NULL DEFAULT 1,   -- ertalab buyurtma → kun
    lead_days_pm  INTEGER      NOT NULL DEFAULT 3,   -- kechqurun buyurtma → kun
    cutoff_hour   INTEGER      NOT NULL DEFAULT 12,
    landed_coef   NUMERIC(8,4) NOT NULL DEFAULT 1,   -- import: bojxona, yetkazish, komissiya
    min_history   INTEGER      NOT NULL DEFAULT 3,   -- nechta priyomkadan keyin ishonchli
    active        BOOLEAN      NOT NULL DEFAULT true,
    supplies_n    INTEGER      NOT NULL DEFAULT 0,
    last_supply   DATE,
    note          VARCHAR(300) NOT NULL DEFAULT ''
);

-- Narx tarixi: qo'lda (zakupshik) va priyomkadan (haqiqiy to'langan)
CREATE TABLE IF NOT EXISTS ombor_narx (
    id            BIGSERIAL PRIMARY KEY,
    agent_ms_id   VARCHAR(40)  NOT NULL,
    product_ms_id VARCHAR(40)  NOT NULL,
    price         BIGINT       NOT NULL,             -- tiyin
    currency      VARCHAR(8)   NOT NULL DEFAULT 'UZS',
    lead_days     INTEGER,
    at_date       DATE         NOT NULL,
    source        VARCHAR(10)  NOT NULL DEFAULT 'QOLDA', -- QOLDA | PRIYOMKA | SHEETS
    note          VARCHAR(300) NOT NULL DEFAULT '',
    created_by    BIGINT,
    UNIQUE (agent_ms_id, product_ms_id, at_date, source)
);
CREATE INDEX IF NOT EXISTS idx_ombor_narx_prod ON ombor_narx(product_ms_id, at_date DESC);

-- Do'kon so'rovlari (sabab kodi bilan), yangi tovar so'rovi, hamkor so'rovi, tender loti
CREATE TABLE IF NOT EXISTS ombor_sorov (
    id            BIGSERIAL PRIMARY KEY,
    kassa_id      BIGINT,
    product_ms_id VARCHAR(40),
    text          VARCHAR(300) NOT NULL DEFAULT '',
    qty           NUMERIC(18,3) NOT NULL DEFAULT 1,
    reason        VARCHAR(12)  NOT NULL DEFAULT 'YOQ',  -- YOQ | KAM | MIJOZ | YANGI | HAMKOR | LOT
    status        VARCHAR(12)  NOT NULL DEFAULT 'YANGI', -- YANGI | KORILDI | QORALAMADA | RAD | BAJARILDI
    by_user_id    BIGINT,
    answer        VARCHAR(300) NOT NULL DEFAULT '',
    answered_by   BIGINT,
    answered_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ombor_sorov_status ON ombor_sorov(status);

-- Buyurtma qoralamasi: QORALAMA → ZAKUPSHIK → ZAVSKLAD → [DIREKTOR] → TASDIQ → YUBORILDI | BEKOR
CREATE TABLE IF NOT EXISTS ombor_qoralama (
    id             BIGSERIAL PRIMARY KEY,
    kassa_id       BIGINT,
    agent_ms_id    VARCHAR(40),
    agent_name     VARCHAR(300) NOT NULL DEFAULT '',
    status         VARCHAR(12)  NOT NULL DEFAULT 'QORALAMA',
    total          BIGINT       NOT NULL DEFAULT 0,   -- tiyin (landed)
    cash_available BIGINT       NOT NULL DEFAULT 0,   -- so'm, tuzilgan paytda
    note           TEXT         NOT NULL DEFAULT '',
    built_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    zakupshik_by   BIGINT,
    zavsklad_by    BIGINT,
    direktor_by    BIGINT,
    sent_by        BIGINT,
    sent_at        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ombor_qoralama_status ON ombor_qoralama(status);

CREATE TABLE IF NOT EXISTS ombor_qoralama_qator (
    id            BIGSERIAL PRIMARY KEY,
    qoralama_id   BIGINT       NOT NULL REFERENCES ombor_qoralama(id) ON DELETE CASCADE,
    product_ms_id VARCHAR(40)  NOT NULL,
    qty           NUMERIC(18,3) NOT NULL DEFAULT 0,
    price         BIGINT       NOT NULL DEFAULT 0,   -- tiyin
    landed_price  BIGINT       NOT NULL DEFAULT 0,   -- tiyin
    basis         VARCHAR(400) NOT NULL DEFAULT '',
    flags         VARCHAR(80)  NOT NULL DEFAULT '',  -- EHTIYOT,NARX_OSHDI,NARX_TUSHDI,SOROV,YETKAZUVCHI_YOQ
    sorov_id      BIGINT
);
CREATE INDEX IF NOT EXISTS idx_ombor_qq ON ombor_qoralama_qator(qoralama_id);

INSERT INTO ombor_qoida(code, title, checker, params, severity, to_role, esc1_min, esc2_min, sort) VALUES
 ('NARX_OSHDI',        'Yetkazuvchi narxni oshirdi',                 'NARX_OZGARISH', '{"scope":"YETKAZUVCHI","dir":"UP","pct":3}',   'MUHIM', 'ZAKUPSHIK', 0, 0, 190),
 ('NARX_TUSHDI',       'Yetkazuvchi narxi tushdi (imkoniyat)',        'NARX_OZGARISH', '{"scope":"YETKAZUVCHI","dir":"DOWN","pct":3}', 'INFO',  'ZAKUPSHIK', 0, 0, 200),
 ('SOROV_JAVOBSIZ',    'Do''kon so''rovi javobsiz',                   'MUDDAT_OTDI',   '{"subject":"sorov","days":1}',                  'OGOH',  'ZAKUPSHIK', 240, 1440, 210),
 ('QORALAMA_KUTMOQDA', 'Buyurtma qoralamasi tasdiqni kutmoqda',       'MUDDAT_OTDI',   '{"subject":"qoralama","days":1}',               'OGOH',  'ZAKUPSHIK', 1440, 0, 220),
 ('HAMKOR_INTERVAL',   'Hamkor do''konda tovar tugagan bo''lishi mumkin', 'KORSATKICH_CHEGARA', '{"code":"HAMKOR_INTERVAL","op":">","threshold":0,"subject":"hamkor_tovar"}', 'INFO', 'ZAKUPSHIK', 0, 0, 230)
ON CONFLICT (code) DO NOTHING;
