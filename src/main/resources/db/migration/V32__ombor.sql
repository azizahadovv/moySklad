-- 🏬 Омбор ёрдамчиси — B0 (docs/OMBOR-TZ.md §3): tovar, ombor, ko'rsatkich, qoida, kamchilik, sinxron.
-- Tamoyil: bitta raqamlar jadvali (ombor_korsatkich), bitta kamchilik hayot sikli (ombor_kamchilik),
-- tekshiruvlar kod emas — ombor_qoida qatorlari.

-- Kassa (do'kon) ↔ MoySklad OMBOR (склад). moysklad_store_id — savdo nuqtasi (retailStore);
-- ombor UUID retailstore.store orqali avtomatik to'ldiriladi, admin qo'lda ham bog'laydi.
ALTER TABLE kassa ADD COLUMN IF NOT EXISTS moysklad_warehouse_id VARCHAR(64);

-- MoySklad omborlari (entity/store)
CREATE TABLE IF NOT EXISTS ombor_store (
    ms_id     VARCHAR(40)  PRIMARY KEY,
    name      VARCHAR(200) NOT NULL DEFAULT '',
    archived  BOOLEAN      NOT NULL DEFAULT false,
    kassa_id  BIGINT,
    sync_at   TIMESTAMPTZ
);

-- Tovarlar (entity/assortment: product / variant / bundle)
CREATE TABLE IF NOT EXISTS ombor_tovar (
    ms_id         VARCHAR(40)  PRIMARY KEY,
    type          VARCHAR(16)  NOT NULL DEFAULT 'product',
    name          VARCHAR(400) NOT NULL DEFAULT '',
    name_norm     VARCHAR(400) NOT NULL DEFAULT '',
    article       VARCHAR(120) NOT NULL DEFAULT '',
    code          VARCHAR(120) NOT NULL DEFAULT '',
    barcode       VARCHAR(120) NOT NULL DEFAULT '',
    folder_name   VARCHAR(400) NOT NULL DEFAULT '',
    uom           VARCHAR(40)  NOT NULL DEFAULT '',
    buy_price     BIGINT       NOT NULL DEFAULT 0,   -- tiyin
    sale_price    BIGINT       NOT NULL DEFAULT 0,   -- tiyin
    min_balance   NUMERIC(18,3) NOT NULL DEFAULT 0,
    archived      BOOLEAN      NOT NULL DEFAULT false,
    ms_updated    TIMESTAMP,
    sync_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ombor_tovar_norm ON ombor_tovar(name_norm) WHERE archived = false;
CREATE INDEX IF NOT EXISTS idx_ombor_tovar_barcode ON ombor_tovar(barcode) WHERE barcode <> '';
CREATE INDEX IF NOT EXISTS idx_ombor_tovar_article ON ombor_tovar(article) WHERE article <> '';

-- Bitta raqamlar jadvali: sana · do'kon (0 = kompaniya) · tovar ('' = jami) · kod · qiymat
CREATE TABLE IF NOT EXISTS ombor_korsatkich (
    date          DATE         NOT NULL,
    kassa_id      BIGINT       NOT NULL DEFAULT 0,
    product_ms_id VARCHAR(40)  NOT NULL DEFAULT '',
    code          VARCHAR(24)  NOT NULL,
    value         NUMERIC(18,3) NOT NULL DEFAULT 0,
    PRIMARY KEY (date, kassa_id, product_ms_id, code)
);
CREATE INDEX IF NOT EXISTS idx_ombor_kors_code ON ombor_korsatkich(code, date);
CREATE INDEX IF NOT EXISTS idx_ombor_kors_prod ON ombor_korsatkich(product_ms_id, code, date);

-- Qoidalar: tekshiruv = qator. checker — 9 umumiy tekshiruvchidan biri, params — JSON matn.
CREATE TABLE IF NOT EXISTS ombor_qoida (
    code          VARCHAR(40)  PRIMARY KEY,
    title         VARCHAR(200) NOT NULL DEFAULT '',
    checker       VARCHAR(32)  NOT NULL,
    params        TEXT         NOT NULL DEFAULT '{}',
    severity      VARCHAR(8)   NOT NULL DEFAULT 'OGOH',   -- INFO | OGOH | MUHIM
    to_role       VARCHAR(12)  NOT NULL DEFAULT 'ZAVSKLAD', -- XODIM | ZAVSKLAD | ZAKUPSHIK | RAHBAR | DIREKTOR | ADMIN
    esc1_min      INTEGER      NOT NULL DEFAULT 240,
    esc2_min      INTEGER      NOT NULL DEFAULT 1440,
    enabled       BOOLEAN      NOT NULL DEFAULT true,
    sort          INTEGER      NOT NULL DEFAULT 100
);

-- Kamchiliklar: har qoida topilmasi bitta qator; ochiq bo'lganda (rule, subject) yagona.
CREATE TABLE IF NOT EXISTS ombor_kamchilik (
    id             BIGSERIAL PRIMARY KEY,
    rule_code      VARCHAR(40)  NOT NULL,
    subject_type   VARCHAR(16)  NOT NULL,   -- tovar | hujjat | sanoq | sorov | qoralama | hamkor | sinxron
    subject_key    VARCHAR(200) NOT NULL,
    kassa_id       BIGINT,
    owner_user_id  BIGINT,
    title          VARCHAR(300) NOT NULL DEFAULT '',
    detail         TEXT         NOT NULL DEFAULT '',
    since          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notified_at    TIMESTAMPTZ,
    esc1_at        TIMESTAMPTZ,
    esc2_at        TIMESTAMPTZ,
    resolved_at    TIMESTAMPTZ,
    resolved_by    BIGINT,
    answer         VARCHAR(40)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ombor_kamchilik_open ON ombor_kamchilik(rule_code, subject_type, subject_key) WHERE resolved_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ombor_kamchilik_open ON ombor_kamchilik(kassa_id) WHERE resolved_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ombor_kamchilik_subject ON ombor_kamchilik(subject_type, subject_key);

-- Sinxron holati (har obyekt turi uchun)
CREATE TABLE IF NOT EXISTS ombor_sinxron (
    entity      VARCHAR(32) PRIMARY KEY,
    cursor_at   TIMESTAMP,
    last_ok_at  TIMESTAMPTZ,
    last_error  TEXT,
    rows_n      INTEGER NOT NULL DEFAULT 0
);

-- B0 qoidalari (sozlanadi: ⚙️ Настройка → 🔗 MoySklad → 🏬 Омбор назорати)
INSERT INTO ombor_qoida(code, title, checker, params, severity, to_role, esc1_min, esc2_min, sort) VALUES
 ('SINXRON_TOXTADI', 'MoySklad ombor sinxroni to''xtadi',           'SINXRON',        '{"max_age_min":90}',                       'MUHIM', 'ADMIN',     0,    0,    10),
 ('QOLDIQ_MANFIY',   'Manfiy qoldiq (sotilgan > kelgan)',           'QOLDIQ_CHEGARA', '{"op":"<","threshold":0}',                 'MUHIM', 'ZAVSKLAD',  120,  480,  20),
 ('TOVAR_DUBLIKAT',  'Dublikat tovar (nom / shtrix-kod / artikul)', 'DUBLIKAT',       '{"keys":"name,barcode,article"}',          'OGOH',  'ZAKUPSHIK', 1440, 2880, 30),
 ('NARX_ANOMAL',     'G''ayrioddiy narx (tannarxdan past yoki x3)', 'NARX_OZGARISH',  '{"scope":"SOTUV","max_markup_pct":300}',   'OGOH',  'ZAKUPSHIK', 1440, 2880, 40)
ON CONFLICT (code) DO NOTHING;
