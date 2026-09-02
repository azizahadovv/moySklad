-- Kunlik kassa solishtirish hisoboti: moliya menejeri tasdig'i (sana bo'yicha bitta).
create table if not exists daily_report_confirm (
    report_date   date primary key,
    user_id       bigint,
    user_name     varchar(120),
    confirmed_at  timestamptz not null default now()
);
