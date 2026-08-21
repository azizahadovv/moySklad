-- Joriy OTDEL(kassa), FOYDALANUVCHI va CLICK hisoblarini yangi (bo'sh) bazada
-- avtomatik yaratish — serverga ko'chirilganda BIR MARTA ishlaydi (Flyway).
-- Mavjud bazada hech narsani buzmaydi: har satr faqat YO'Q bo'lsa qo'shiladi,
-- keyinchalik qo'shish odatdagidek bot/jadval orqali davom etadi.
-- DIQQAT: balans va operatsiyalar TARIXINI ko'chirmaydi — to'liq ko'chirish
-- uchun pg_dump/pg_restore ishlatiladi.

-- ============ KASSALAR (otdellar) ============
INSERT INTO kassa(name, moysklad_group_id, active)
SELECT 'Отдел Зуфар', 'cde19a78-4108-11ef-0a80-0910001fa2b7', true
WHERE NOT EXISTS (SELECT 1 FROM kassa WHERE name = 'Отдел Зуфар');

INSERT INTO kassa(name, moysklad_group_id, active)
SELECT 'Отдел Самойиддин', 'cde1bf03-4108-11ef-0a80-0910001fa2b8', true
WHERE NOT EXISTS (SELECT 1 FROM kassa WHERE name = 'Отдел Самойиддин');

INSERT INTO kassa(name, moysklad_group_id, active)
SELECT 'Отдел Камера', '3c554a84-d106-11ef-0a80-02380023a227', true
WHERE NOT EXISTS (SELECT 1 FROM kassa WHERE name = 'Отдел Камера');

-- Отдел Али — ataylab MoySklad otdeliga BOG'LANMAGAN (qo'lda yuritiladi)
INSERT INTO kassa(name, moysklad_group_id, active)
SELECT 'Отдел Али', NULL, true
WHERE NOT EXISTS (SELECT 1 FROM kassa WHERE name = 'Отдел Али');

INSERT INTO kassa(name, moysklad_group_id, active)
SELECT 'Ортик', '5f30833f-6fa9-11f0-0a80-141a001a42b7', false
WHERE NOT EXISTS (SELECT 1 FROM kassa WHERE name = 'Ортик');

INSERT INTO kassa(name, moysklad_group_id, active)
SELECT 'Шохрух', '6f96acb2-6fa9-11f0-0a80-08f0001b0252', false
WHERE NOT EXISTS (SELECT 1 FROM kassa WHERE name = 'Шохрух');

-- ============ FOYDALANUVCHILAR ============
-- Telegram'lilar telegram_id bo'yicha, telegram'sizlar ism bo'yicha himoyalangan.
INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT 7613051212, 'Azizbek Ahadov', '998978611199', 'SUPERADMIN', NULL, true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE telegram_id = 7613051212);

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT 1319015588, 'Яшинбек Ярашов', NULL, 'SUPERADMIN', NULL, true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE telegram_id = 1319015588);

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT 6716477236, 'Амирбек Бахшуллоев', NULL, 'KASSIR', NULL, true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE telegram_id = 6716477236);

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT 1034672698, 'Sattorov Samoyiddin', '998333000226', 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Камера' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE telegram_id = 1034672698);

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT 8202301127, 'Sardor Samadov', NULL, 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Самойиддин' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE telegram_id = 8202301127);

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT 6560480754, 'Ali Ibodullayev', NULL, 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Али' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE telegram_id = 6560480754);

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT NULL, 'Raxmatov Jahongir', '998991561606', 'SUPERADMIN', NULL, true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE full_name = 'Raxmatov Jahongir');

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT NULL, 'Raxmonov Ozodbek', NULL, 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Али' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE full_name = 'Raxmonov Ozodbek');

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT NULL, 'Sharipov Shoxrux', NULL, 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Али' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE full_name = 'Sharipov Shoxrux');

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT NULL, 'Zulxumor', NULL, 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Камера' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE full_name = 'Zulxumor');

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT NULL, 'Xayrullayev Abdullo', NULL, 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Зуфар' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE full_name = 'Xayrullayev Abdullo');

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT NULL, 'Dilshod (Abdullo)', NULL, 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Зуфар' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE full_name = 'Dilshod (Abdullo)');

INSERT INTO users(telegram_id, full_name, phone, role, kassa_id, active)
SELECT NULL, 'Ismatov Bobomurod', '998990290770', 'KASSIR',
       (SELECT id FROM kassa WHERE name = 'Отдел Самойиддин' LIMIT 1), true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE full_name = 'Ismatov Bobomurod');

-- ============ CLICK HISOBLARI ============
INSERT INTO click_accounts(name, active)
SELECT v.n, true FROM (VALUES ('Зуфар ака Клик'), ('Бобомурод Клик'), ('Жасур ака Клик'),
                              ('Озод Клик'), ('Абдулло ака Клик'), ('Камера дукон Клик')) v(n)
WHERE NOT EXISTS (SELECT 1 FROM click_accounts c WHERE c.name = v.n);
