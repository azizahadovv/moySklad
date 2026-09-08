-- MoySklad API barcha vaqtlarni MOSKVA vaqtida (UTC+3) qaytaradi va filtrlarni ham shunday tushunadi
-- (2026-09-08 aniqlandi: 16:30 Toshkentda yaratilgan kontragent API'da 14:27). Nazorat moduli shu paytgacha
-- bu vaqtlarni Toshkent vaqti deb saqlagan — mavjud yozuvlar +2 soat tuzatiladi (Moskva UTC+3, Toshkent UTC+5,
-- ikkalasida ham yozgi vaqt yo'q). Kod endi o'qishda Moskva → Toshkent, filtrda Toshkent → Moskva o'giradi.
UPDATE shipments SET moment = moment + interval '2 hours' WHERE moment IS NOT NULL;
UPDATE shipments SET ms_created = ms_created + interval '2 hours' WHERE ms_created IS NOT NULL;
UPDATE shipments SET ms_updated = ms_updated + interval '2 hours' WHERE ms_updated IS NOT NULL;
UPDATE agent_checks SET ms_created_at = ms_created_at + interval '2 hours' WHERE ms_created_at IS NOT NULL;
UPDATE agent_checks SET ms_updated_at = ms_updated_at + interval '2 hours' WHERE ms_updated_at IS NOT NULL;
UPDATE ms_agents SET ms_updated = ms_updated + interval '2 hours' WHERE ms_updated IS NOT NULL;

-- Ko'r davr (15:13–16:40 Toshkent) hujjatlari qayta o'qilsin — watermark orqaga
UPDATE settings SET value = '2026-09-08 15:00:00' WHERE key IN ('control.last_demand_sync', 'control.last_agent_sync');
