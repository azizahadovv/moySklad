-- Qarz eslatmalariga qisman to'lov: avtomatik (MoySklad balansidan) va
-- qo'lda (buxgalter/admin tasdig'i bilan) kiritilgan to'lovlar.
ALTER TABLE reminders
    ADD COLUMN repaid                BIGINT NOT NULL DEFAULT 0,  -- amalda hisoblangan jami to'langan (avto/qo'lda ichidan kattasi)
    ADD COLUMN repaid_manual         BIGINT NOT NULL DEFAULT 0,  -- tasdiqlangan qo'lda to'lovlar yig'indisi
    ADD COLUMN pending_manual_amount BIGINT,                     -- tasdiq kutayotgan qo'lda to'lov
    ADD COLUMN pending_manual_by     BIGINT REFERENCES users(id);
