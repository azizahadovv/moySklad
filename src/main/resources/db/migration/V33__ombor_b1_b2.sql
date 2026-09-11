-- 🏬 Омбор B1 (kunlik tozalik) + B2 (sotuv tarixi, ABC, buyurtma nuqtasi, fill rate) — docs/OMBOR-TZ.md §12

-- Ombor hujjatlari (move/supply/purchaseorder/invoicein/purchasereturn/inventory/loss/enter/salesreturn/retailsalesreturn)
CREATE TABLE IF NOT EXISTS ombor_hujjat (
    id                 BIGSERIAL PRIMARY KEY,
    ms_id              VARCHAR(40)  NOT NULL UNIQUE,
    type               VARCHAR(24)  NOT NULL,
    doc_no             VARCHAR(40)  NOT NULL DEFAULT '',
    moment             TIMESTAMP,
    store_ms_id        VARCHAR(40),
    target_store_ms_id VARCHAR(40),
    kassa_id           BIGINT,
    target_kassa_id    BIGINT,
    agent_ms_id        VARCHAR(40),
    agent_name         VARCHAR(300) NOT NULL DEFAULT '',
    state              VARCHAR(80)  NOT NULL DEFAULT '',
    applicable         BOOLEAN,                        -- NULL: bu turda yo'q (inventory)
    sum                BIGINT       NOT NULL DEFAULT 0, -- tiyin
    payed_sum          BIGINT       NOT NULL DEFAULT 0, -- tiyin
    owner_uid          VARCHAR(80),
    owner_name         VARCHAR(200) NOT NULL DEFAULT '',
    owner_user_id      BIGINT,
    links              TEXT         NOT NULL DEFAULT '', -- bog'liq hujjatlar ms_id CSV (purchaseorder→supplies, loss→inventory …)
    positions_n        INTEGER      NOT NULL DEFAULT 0,
    corrections_n      INTEGER      NOT NULL DEFAULT 0, -- inventory: farqli pozitsiyalar
    deleted            BOOLEAN      NOT NULL DEFAULT false,
    ms_created         TIMESTAMP,
    ms_updated         TIMESTAMP,
    sync_at            TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ombor_hujjat_type ON ombor_hujjat(type, moment);
CREATE INDEX IF NOT EXISTS idx_ombor_hujjat_appl ON ombor_hujjat(applicable) WHERE applicable = false;
CREATE INDEX IF NOT EXISTS idx_ombor_hujjat_kassa ON ombor_hujjat(kassa_id);

CREATE TABLE IF NOT EXISTS ombor_pozitsiya (
    hujjat_id      BIGINT       NOT NULL REFERENCES ombor_hujjat(id) ON DELETE CASCADE,
    product_ms_id  VARCHAR(40)  NOT NULL,
    qty            NUMERIC(18,3) NOT NULL DEFAULT 0,
    price          BIGINT       NOT NULL DEFAULT 0,   -- tiyin
    calculated_qty NUMERIC(18,3),                     -- inventory: hisobdagi
    PRIMARY KEY (hujjat_id, product_ms_id)
);
CREATE INDEX IF NOT EXISTS idx_ombor_poz_product ON ombor_pozitsiya(product_ms_id);

-- Davrlar: AKSIYA / MAVSUM / SOVISH (aksiyadan keyin avtomatik) / YANGI (sinov partiyasi)
CREATE TABLE IF NOT EXISTS ombor_davr (
    id             BIGSERIAL PRIMARY KEY,
    kind           VARCHAR(12)  NOT NULL,
    product_ms_id  VARCHAR(40),
    folder_name    VARCHAR(400),
    code           VARCHAR(40)  NOT NULL DEFAULT '',
    from_date      DATE         NOT NULL,
    to_date        DATE         NOT NULL,
    note           VARCHAR(300) NOT NULL DEFAULT '',
    created_by     BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ombor_davr_prod ON ombor_davr(product_ms_id, kind);

-- Rotatsion sanoq (пересчёт): tunlik reja, kladovchi kiritadi va tasdiqlaydi
CREATE TABLE IF NOT EXISTS ombor_sanoq (
    id             BIGSERIAL PRIMARY KEY,
    kassa_id       BIGINT       NOT NULL,
    product_ms_id  VARCHAR(40)  NOT NULL,
    plan_date      DATE         NOT NULL,
    abc            CHAR(1)      NOT NULL DEFAULT 'C',
    system_qty     NUMERIC(18,3) NOT NULL DEFAULT 0,
    fact_qty       NUMERIC(18,3),
    status         VARCHAR(10)  NOT NULL DEFAULT 'REJA',   -- REJA | KIRITILDI | TASDIQ
    by_user_id     BIGINT,
    at             TIMESTAMPTZ,
    UNIQUE (kassa_id, product_ms_id, plan_date)
);
CREATE INDEX IF NOT EXISTS idx_ombor_sanoq_open ON ombor_sanoq(kassa_id, plan_date) WHERE status <> 'TASDIQ';

-- B1 + B2 qoidalari
INSERT INTO ombor_qoida(code, title, checker, params, severity, to_role, esc1_min, esc2_min, sort) VALUES
 ('HARAKAT_OTKAZILMAGAN', 'O''tkazilmagan tovar harakati (kun oxiri)', 'HUJJAT_OTKAZILMAGAN', '{"types":"move,supply,loss,enter,salesreturn,purchasereturn","age_min":120,"until_hour":18}', 'OGOH', 'XODIM', 60, 240, 50),
 ('QABUL_FARQ',        'Qabul farqi: buyurtma ↔ priyomka',            'HUJJAT_FARQ',  '{"mode":"link","from":"purchaseorder","to":"supply","days":2}',          'MUHIM', 'ZAVSKLAD', 240, 1440, 60),
 ('INVENT_FARQ',       'Inventarizatsiya farqi yopilmagan (enter/loss yo''q)', 'HUJJAT_FARQ', '{"mode":"inventory","days":2}',                                 'OGOH',  'ZAVSKLAD', 1440, 2880, 70),
 ('QAYTARISH_OSILDI',  'Yetkazuvchiga qaytarish osilib qoldi (kompensatsiya yo''q)', 'MUDDAT_OTDI', '{"subject":"hujjat","type":"purchasereturn","cond":"unpaid","days":14}', 'OGOH', 'ZAKUPSHIK', 1440, 2880, 80),
 ('MIJOZ_QAYTARISH',   'Mijoz qaytarishi tasniflanmagan',             'MUDDAT_OTDI',  '{"subject":"hujjat","type":"salesreturn,retailsalesreturn","cond":"any","days":0,"once":true,"answers":"ALMASHTIRISH,BRAK,MUDDATI_OTGAN"}', 'OGOH', 'XODIM', 240, 1440, 90),
 ('SPISANIYA_TASDIQ',  'Spisaniya (loss) rahbar tasdig''ini kutmoqda', 'MUDDAT_OTDI', '{"subject":"hujjat","type":"loss","cond":"any","days":0,"once":true,"answers":"TASDIQ"}', 'MUHIM', 'RAHBAR', 0, 1440, 100),
 ('SANOQ_TASDIQLANMAGAN', 'Sanoq tasdiqlanmagan (kun yopilmaydi)',     'MUDDAT_OTDI',  '{"subject":"sanoq","until_hour":18}',                                    'MUHIM', 'ZAVSKLAD', 60, 180, 110),
 ('SANOQ_FARQ',        'Sanoq farqi (fakt ≠ hisob)',                  'HUJJAT_FARQ',  '{"mode":"sanoq","days":7}',                                              'OGOH',  'RAHBAR', 0, 0, 120),
 ('HAMKOR_QARZ',       'Hamkor do''kon qarzi limitdan oshdi',          'KORSATKICH_CHEGARA', '{"code":"HAMKOR_QARZ","op":">","threshold":5000000,"subject":"hamkor"}', 'OGOH', 'ZAKUPSHIK', 1440, 0, 130),
 ('QOLDIQ_MIN',        'Minimal qoldiqdan past (tovar kartochkasi)',   'QOLDIQ_CHEGARA', '{"op":"<","threshold":"min_balance"}',                                 'OGOH',  'ZAKUPSHIK', 1440, 0, 140),
 ('BUYURTMA_NUQTA',    'Buyurtma nuqtasiga yetdi (qoralamaga nomzod)', 'QOLDIQ_CHEGARA', '{"op":"<=","threshold":"BUYURTMA_NUQTA","abc_in":"A,B","skip_davr":"SOVISH,YANGI"}', 'INFO', 'ZAKUPSHIK', 0, 0, 150),
 ('NELIKVID',          'Neliqvid: 90 kundan beri harakatsiz',          'KORSATKICH_CHEGARA', '{"code":"AYLANMA_KUN","op":">","threshold":90,"scope":"company","min_stock":1}', 'INFO', 'ZAKUPSHIK', 0, 0, 160),
 ('KOCHIRISH',         'Do''konlar orasida ko''chirish taklifi',        'KOCHIRISH_TAKLIF', '{"cover_days_from":60,"abc_in":"A,B"}',                              'INFO',  'ZAVSKLAD', 0, 0, 170),
 ('FILL_PAST',         'Fill rate past (A tovarlar, 30 kun)',          'KORSATKICH_CHEGARA', '{"code":"FILL_RATE_30","op":"<","threshold":95,"subject":"kassa"}',   'INFO',  'RAHBAR', 0, 0, 180)
ON CONFLICT (code) DO NOTHING;
