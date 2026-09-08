-- 🕵️ Nazorat: jonli yuklashda 38 ta otgruzka «value too long for type character varying(40)» bilan
-- yozilmadi — kontragentda bir nechta telefon (vergul bilan) bo'lganda. Maydonlar kengaytirildi;
-- kod ham qisqartirib yozadi.
ALTER TABLE shipments ALTER COLUMN agent_phone TYPE VARCHAR(160);
ALTER TABLE shipments ALTER COLUMN doc_no TYPE VARCHAR(80);
ALTER TABLE shipments ALTER COLUMN state TYPE VARCHAR(120);
ALTER TABLE shipments ALTER COLUMN agent_name TYPE VARCHAR(400);
ALTER TABLE agent_checks ALTER COLUMN agent_name TYPE VARCHAR(400);
ALTER TABLE ms_agents ALTER COLUMN name TYPE VARCHAR(400);
ALTER TABLE ms_agents ALTER COLUMN inn TYPE VARCHAR(40);
