-- 🏬 Ombor: "Monoblok H610" vs "Monoblok H610 White" kabi bir necha so'z farqi bilan nomlangan
-- tovarlarni ham dublikat nomzod sifatida topish (foydalanuvchi so'rovi 2026-09-15). Mavjud "DUBLIKAT"
-- checker'ning o'zi — endi keys ro'yxatiga "name_fuzzy" qo'shilsa nomlarni so'z(token) darajasida
-- Jaccard o'xshashlik bilan solishtiradi (aynan bir xil emas, lekin ko'p so'zi mos). Alohida qoida —
-- aniq dublikat (TOVAR_DUBLIKAT, "MUHIM"dan past emas darajada aniq) bilan ehtimoliy dublikatni
-- (inson tekshiruvi kerak, "OGOH") aralashtirmaslik uchun.
INSERT INTO ombor_qoida(code, title, checker, params, severity, to_role, esc1_min, esc2_min, sort) VALUES
 ('TOVAR_DUBLIKAT_EHTIMOL', 'Ehtimoliy dublikat tovar (nomi o''xshash, lekin bir xil emas)', 'DUBLIKAT',
  '{"keys":"name_fuzzy"}', 'OGOH', 'ZAKUPSHIK', 1440, 2880, 35)
ON CONFLICT (code) DO NOTHING;
