-- Ba'zi "kassa" yozuvlari haqiqiy naqd kassa emas — faqat kontragent (qarz daftari)
-- xodimlarini guruhlash uchun ishlatiladi (masalan "Отдел Али"). Ular hech qachon
-- MoySklad orqali pul qabul qilmaydi, shuning uchun pul hisobotlarida (Бугунги
-- тушум, Баланс) ko'rsatilmasligi kerak.
ALTER TABLE kassa ADD COLUMN cashless BOOLEAN NOT NULL DEFAULT false;

UPDATE kassa SET cashless = true WHERE name = 'Отдел Али';
