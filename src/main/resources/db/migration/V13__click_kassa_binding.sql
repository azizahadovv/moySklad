-- Click hisoblarini otdel (kassa)larga bog'lash — balans/hisobotlarda kliklar
-- otdel kesimida guruhlanib ko'rsatiladi. Bog'lanmagan hisob «Boshqa»da qoladi.
ALTER TABLE click_accounts ADD COLUMN kassa_id BIGINT;

-- User qarori 28.08.2026: «Озод Клик» → «Клик Самойиддин»
UPDATE click_accounts SET name = 'Клик Самойиддин' WHERE name = 'Озод Клик';

UPDATE click_accounts c SET kassa_id = k.id FROM kassa k
    WHERE k.name = 'Отдел Зуфар' AND c.name IN ('Зуфар ака Клик', 'Бобомурод Клик');
UPDATE click_accounts c SET kassa_id = k.id FROM kassa k
    WHERE k.name = 'Отдел Самойиддин' AND c.name = 'Клик Самойиддин';
UPDATE click_accounts c SET kassa_id = k.id FROM kassa k
    WHERE k.name = 'Отдел Абдулло' AND c.name = 'Абдулло ака Клик';
UPDATE click_accounts c SET kassa_id = k.id FROM kassa k
    WHERE k.name = 'Отдел Камера' AND c.name = 'Камера дукон Клик';
-- «Жасур ака Клик» ataylab bog'lanmagan (kassa_id NULL) qoladi.
