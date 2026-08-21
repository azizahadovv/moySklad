-- Kontragent qarz eslatmalari (Отдел Али / barcha xodimlar uchun qarz daftari).
-- Har bir yozuv: kimdan/kimga, qancha, muddat, necha kun oldin eslatish,
-- kimlarga xabar borishi. Bitta kontragentga bir nechta yozuv bo'lishi mumkin.
CREATE TABLE reminders (
    id              BIGSERIAL PRIMARY KEY,
    creator_user_id BIGINT NOT NULL REFERENCES users(id),
    agent_ms_id     VARCHAR(40),                 -- MoySklad kontragent UUID (qo'lda kiritilganda NULL)
    agent_name      VARCHAR(200) NOT NULL,
    agent_info      VARCHAR(200),                -- telefon / INN (ko'rsatish uchun)
    direction       VARCHAR(12) NOT NULL,        -- BIZ_QARZDOR | U_QARZDOR
    amount          BIGINT NOT NULL,             -- so'm
    due_date        DATE NOT NULL,
    comment         TEXT,
    remind_days     VARCHAR(60) NOT NULL DEFAULT '',   -- "3,1" — necha kun oldin
    recipients      VARCHAR(300) NOT NULL DEFAULT '',  -- users.id CSV
    status          VARCHAR(12) NOT NULL DEFAULT 'FAOL',  -- FAOL | BAJARILDI | BEKOR
    last_notified   DATE,                        -- shu kunga xabar yuborilgan (dublikat bo'lmasin)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reminders_status_due ON reminders(status, due_date);
CREATE INDEX idx_reminders_agent ON reminders(agent_ms_id);
