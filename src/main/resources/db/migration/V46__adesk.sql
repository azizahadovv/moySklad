-- 📒 Adesk ↔ MoySklad integratsiyasi (2026-10-05, docs/ADESK.md).
-- Har bir bog'langan obyekt bitta qator: MoySklad kaliti (ms_key) ↔ Adesk id.
--   kind: ORG (yuridik shaxs), ACCOUNT (hisob: "<orgId>:CASH" yoki "<orgId>:<accountId>"),
--         CATEGORY ("in:<nom>" / "out:<nom>"), CONTRACTOR (kontragent), EMPLOYEE (xodim),
--         ORGC (o'z yuridik shaxsimiz kontragent sifatida), PRODUCT (tovar/xizmat),
--         MONEY (pul hujjati ↔ Adesk operatsiyasi), COMMIT (otgruzka/priyomka/vozvrat ↔ Adesk обязательство).
--   hash  — oxirgi yuborilgan ma'lumot izi: o'zgarmagan hujjat qayta yuborilmaydi.
--   status — OK / ERROR (Adesk rad etdi, error ustunida sabab) / DELETED (MoySklad'da o'chdi, Adesk'dan olindi) / SKIP.
--   origin — MS (MoySklad'dan Adesk'ka) yoki AD (Adesk'da kiritilgan, MoySklad'ga yozilgan).
CREATE TABLE IF NOT EXISTS adesk_link (
    id           BIGSERIAL PRIMARY KEY,
    kind         VARCHAR(16)  NOT NULL,
    ms_key       VARCHAR(160) NOT NULL,
    adesk_id     BIGINT,
    name         VARCHAR(300),
    hash         VARCHAR(64),
    ms_type      VARCHAR(24),
    doc_date     DATE,
    sum_tiyin    BIGINT,
    account_key  VARCHAR(100),
    status       VARCHAR(12)  NOT NULL DEFAULT 'OK',
    error        TEXT,
    origin       VARCHAR(4)   NOT NULL DEFAULT 'MS',
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_adesk_link UNIQUE (kind, ms_key)
);
CREATE INDEX IF NOT EXISTS ix_adesk_link_adesk ON adesk_link (kind, adesk_id);
CREATE INDEX IF NOT EXISTS ix_adesk_link_date  ON adesk_link (kind, doc_date);
CREATE INDEX IF NOT EXISTS ix_adesk_link_status ON adesk_link (status);
