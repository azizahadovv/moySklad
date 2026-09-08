-- Bevosita qabul (TOPSHIRIQ, NAQD) endi avtomatik QABUL holatidagi hisobot (submission)
-- bilan yoziladi (SubmissionService.directCollect). Bu migratsiya AVVAL yozilgan, hisobotsiz
-- qolgan bevosita qabullarni shu modelga keltiradi: har biriga QABUL hisoboti yaratiladi,
-- operatsiya unga bog'lanadi, «qaysi kun uchun» kuni (op_date) hisobot kunlariga qo'shiladi.
-- Idempotent: submission_id bo'lgan operatsiyalarga tegilmaydi.

WITH src AS (
    SELECT o.id, o.from_owner_id AS kassa_id, o.amount, o.created_by, o.decided_by,
           o.created_at, COALESCE(o.decided_at, o.created_at) AS decided_at, o.op_date,
           CASE WHEN o.comment LIKE 'Topshirdi: %' THEN substring(o.comment FROM 12) ELSE '' END AS who
    FROM operations o
    WHERE o.type = 'TOPSHIRIQ' AND o.money_type = 'NAQD' AND o.status = 'TASDIQLANGAN'
      AND o.from_owner_type = 'KASSA' AND o.submission_id IS NULL
),
ins AS (
    INSERT INTO submissions (kassa_id, naqd, klik, accepted_naqd, accepted_klik, status,
                             submitted_by, decided_by, comment, created_at, decided_at)
    SELECT s.kassa_id, s.amount, 0, s.amount, 0, 'QABUL',
           COALESCE((SELECT u.id FROM users u
                     WHERE u.kassa_id = s.kassa_id AND u.active
                       AND lower(u.full_name) = lower(s.who) LIMIT 1),
                    s.decided_by, s.created_by, (SELECT min(id) FROM users)),
           s.decided_by,
           'Бевосита қабул — топширди: ' || s.who || ' · ' || s.op_date,
           s.created_at, s.decided_at
    FROM src s ORDER BY s.id
    RETURNING id, kassa_id, created_at
)
UPDATE operations o SET submission_id = i.id
FROM ins i, src s
WHERE o.id = s.id AND s.kassa_id = i.kassa_id AND s.created_at = i.created_at;

INSERT INTO submission_days (submission_id, day_id)
SELECT o.submission_id, d.id
FROM operations o
JOIN submissions s ON s.id = o.submission_id
JOIN days d ON d.kassa_id = o.from_owner_id AND d.date = o.op_date
WHERE o.type = 'TOPSHIRIQ' AND s.comment LIKE 'Бевосита қабул%'
  AND NOT EXISTS (SELECT 1 FROM submission_days sd WHERE sd.submission_id = o.submission_id);
