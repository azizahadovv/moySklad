-- 🕵️ Контрагент назорати (docs/KONTRAGENT-NAZORAT.md):
--   A) kontragent sifat nazorati (majburiy maydonlar, dublikat) — yaratgan xodimga xabar;
--   B) otgruzka to'lov nazorati — 2 soatda to'lanmasa qarzdorlar ro'yxati, 20 daqiqada balans tekshiruvi.

-- Xodim ↔ MoySklad xodimi bog'lanishi (audit uid = login, employee UUID)
ALTER TABLE users ADD COLUMN IF NOT EXISTS ms_employee_id VARCHAR(40);
ALTER TABLE users ADD COLUMN IF NOT EXISTS ms_uid VARCHAR(80);

-- Otdel (kassa) rahbarlari — o'z otdeli qarzdorlari va kontragent xatolarini oladi
CREATE TABLE IF NOT EXISTS kassa_heads (
    id        BIGSERIAL PRIMARY KEY,
    kassa_id  BIGINT NOT NULL REFERENCES kassa(id),
    user_id   BIGINT NOT NULL REFERENCES users(id),
    UNIQUE (kassa_id, user_id)
);

-- MoySklad kontragentlari indeksi (dublikat tekshiruvi uchun: telefon / nom)
CREATE TABLE IF NOT EXISTS ms_agents (
    ms_id       VARCHAR(40) PRIMARY KEY,
    name        VARCHAR(300) NOT NULL DEFAULT '',
    phone_norm  VARCHAR(20)  NOT NULL DEFAULT '',
    inn         VARCHAR(20)  NOT NULL DEFAULT '',
    archived    BOOLEAN      NOT NULL DEFAULT false,
    ms_updated  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ms_agents_phone ON ms_agents(phone_norm);
CREATE INDEX IF NOT EXISTS idx_ms_agents_name ON ms_agents(lower(name));

-- A) kontragent tekshiruvlari
CREATE TABLE IF NOT EXISTS agent_checks (
    id              BIGSERIAL PRIMARY KEY,
    agent_ms_id     VARCHAR(40)  NOT NULL UNIQUE,
    agent_name      VARCHAR(300) NOT NULL DEFAULT '',
    created_uid     VARCHAR(80),                 -- MoySklad login (audit)
    creator_user_id BIGINT,                      -- bot foydalanuvchisi (bog'langan bo'lsa)
    kassa_id        BIGINT,                      -- kontragent otdeli → kassa
    ms_created_at   TIMESTAMP,
    ms_updated_at   TIMESTAMP,
    violations      TEXT         NOT NULL DEFAULT '',   -- K1,K3,... kodlar CSV
    status          VARCHAR(12)  NOT NULL DEFAULT 'OK', -- OK | OCHIQ | TUZATILDI | ETIBORSIZ
    notified_at     TIMESTAMPTZ,
    last_daily      DATE,
    escalated_at    TIMESTAMPTZ,
    fixed_at        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_checks_status ON agent_checks(status);

-- B) otgruzkalar nazorati
CREATE TABLE IF NOT EXISTS shipments (
    id              BIGSERIAL PRIMARY KEY,
    ms_id           VARCHAR(40)  NOT NULL UNIQUE,
    doc_no          VARCHAR(40)  NOT NULL DEFAULT '',
    moment          TIMESTAMP,
    ms_created      TIMESTAMP,
    ms_updated      TIMESTAMP,
    agent_ms_id     VARCHAR(40),
    agent_name      VARCHAR(300) NOT NULL DEFAULT '',
    agent_phone     VARCHAR(40)  NOT NULL DEFAULT '',
    owner_ms_id     VARCHAR(40),
    owner_uid       VARCHAR(80),
    owner_name      VARCHAR(200) NOT NULL DEFAULT '',
    owner_user_id   BIGINT,
    masul           VARCHAR(200) NOT NULL DEFAULT '',
    masul_user_id   BIGINT,
    ms_group_id     VARCHAR(40),
    kassa_id        BIGINT,
    sum             BIGINT       NOT NULL DEFAULT 0,    -- so'm
    payed_sum       BIGINT       NOT NULL DEFAULT 0,    -- so'm
    agent_balance   BIGINT,                             -- so'm (manfiy = bizga qarzdor)
    state           VARCHAR(60)  NOT NULL DEFAULT '',
    due_at          DATE,                               -- «Тўлов муддати»
    comment         TEXT,
    control_status  VARCHAR(12)  NOT NULL DEFAULT 'KUTILMOQDA', -- KUTILMOQDA|TOLANGAN|QARZ|YOPILDI|BEKOR
    check_at        TIMESTAMPTZ,
    debt_since      TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    close_reason    VARCHAR(300),
    closed_by       BIGINT,
    last_daily      DATE,
    reminder_id     BIGINT,
    silent          BOOLEAN      NOT NULL DEFAULT false, -- eski qarz: xabarsiz yuklangan
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_shipments_status ON shipments(control_status);
CREATE INDEX IF NOT EXISTS idx_shipments_owner ON shipments(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_shipments_kassa ON shipments(kassa_id);
CREATE INDEX IF NOT EXISTS idx_shipments_agent ON shipments(agent_ms_id);

-- Qarz daftari: avtomatik (otgruzka) yozuvlar qo'lda yozuvlar bilan bitta ro'yxatda
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS source VARCHAR(12) NOT NULL DEFAULT 'QOLDA';
ALTER TABLE reminders ADD COLUMN IF NOT EXISTS shipment_id BIGINT;
