-- Qarzdor eslatmalari (takror): har otgruzka uchun oxirgi eslatma yuborilgan kun — kuniga bir marta.
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS remind_sent DATE;
