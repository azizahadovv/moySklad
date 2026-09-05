-- 🔘 Shablon tugmasi: bildirishnoma (shablon) tanlangan rollar uchun ASOSIY MENYUDA tugma
-- bo'lib chiqadi; bosilganda foydalanuvchi kontekstida jonli render qilinadi.
-- Mavjud menyular/hisobotlarga tegilmaydi — tugmalar oxiriga qo'shiladi.
alter table notifies add column if not exists button_label varchar(60)  not null default '';
alter table notifies add column if not exists button_roles varchar(60)  not null default '';
