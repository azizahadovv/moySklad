-- Otdel yonida ko'rsatiladigan do'kon/xizmat nomi (Click hisoboti shabloni:
-- «🏪 Отдел Зуфар | Компьютер дукон»). SuperAdmin /dukon <id> <nom> bilan o'zgartiradi.
alter table kassa add column if not exists shop_label varchar(100);

update kassa set shop_label = 'Компьютер дукон' where name = 'Отдел Зуфар'      and shop_label is null;
update kassa set shop_label = 'Сервис 1'        where name = 'Отдел Самойиддин' and shop_label is null;
update kassa set shop_label = 'Камера дукон'    where name = 'Отдел Камера'     and shop_label is null;
update kassa set shop_label = 'Сервис 2'        where name = 'Отдел Абдулло'    and shop_label is null;
