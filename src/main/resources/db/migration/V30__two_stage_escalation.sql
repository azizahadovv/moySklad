-- Ikki bosqichli eskalatsiya (user 09.09.2026): xato → xodim; 35 min → otdel rahbari; 60 min → admin + rahbar.
ALTER TABLE agent_checks ADD COLUMN IF NOT EXISTS escalated2_at TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS issues_escalated_at TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS issues_escalated2_at TIMESTAMPTZ;
