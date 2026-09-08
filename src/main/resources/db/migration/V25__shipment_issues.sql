-- 🕵️ Nazorat: otgruzka kamchiliklari (O1 «Тўлов муддати» yo'q, O2 «Масъул» yo'q, O3 status yo'q,
-- O4 izoh yo'q, O5 kontragent telefoni yo'q) — qarzdagi otgruzkalar uchun, xodimga guruhlangan xabar.
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS issues VARCHAR(60) NOT NULL DEFAULT '';
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS issues_since TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS issues_notified_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_shipments_issues ON shipments(issues) WHERE issues <> '';
