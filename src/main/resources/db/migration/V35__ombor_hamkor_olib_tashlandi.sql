-- 🏬 Омбор: 🤝 Ҳамкорлар bo'limi olib tashlandi (2026-09-12, user qarori) — qoidalar, ko'rsatkichlar, sozlama
UPDATE ombor_kamchilik SET resolved_at = now(), answer = 'bo''lim olib tashlandi'
 WHERE rule_code IN ('HAMKOR_QARZ', 'HAMKOR_INTERVAL') AND resolved_at IS NULL;
DELETE FROM ombor_qoida WHERE code IN ('HAMKOR_QARZ', 'HAMKOR_INTERVAL');
DELETE FROM ombor_korsatkich WHERE code IN ('HAMKOR_QARZ', 'HAMKOR_INTERVAL');
DELETE FROM settings WHERE key = 'ombor.hamkor_tag';
