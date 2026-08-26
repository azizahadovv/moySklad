-- Har bir Click hisobini MoySklad'dagi haqiqiy "organizationAccount"ga bog'lash —
-- shundan keyin "Входящий платеж" (paymentin, Клик statusli) hujjatlari otdel
-- o'rniga aynan shu hisobga (kim qabul qilgani) yoziladi, aralashib ketmaydi.
ALTER TABLE click_accounts ADD COLUMN moysklad_account_id VARCHAR(40);

UPDATE click_accounts SET moysklad_account_id = 'da4ae126-547d-11ef-0a80-088a0004ad22' WHERE name = 'Зуфар ака Клик';
UPDATE click_accounts SET moysklad_account_id = 'd7fdfe34-8508-11f1-0a80-01740011aba1' WHERE name = 'Бобомурод Клик';
UPDATE click_accounts SET moysklad_account_id = 'dc3f86db-435c-11ef-0a80-0915000ea018' WHERE name = 'Жасур ака Клик';
UPDATE click_accounts SET moysklad_account_id = '3f2c89b7-ab22-11f0-0a80-0d5400047309' WHERE name = 'Озод Клик';
UPDATE click_accounts SET moysklad_account_id = '85986031-5545-11ef-0a80-0da5001d37cf' WHERE name = 'Абдулло ака Клик';
UPDATE click_accounts SET moysklad_account_id = 'e2689533-4243-11f1-0a80-18f400383fb7' WHERE name = 'Камера дукон Клик';
