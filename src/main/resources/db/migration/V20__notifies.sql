-- 🔔 Bildirishnomalar: admin tomonidan yaratiladigan shablonli, jadvalli xabarlar
-- (userlar / guruhlar / kanallar). Mavjud Click/kunlik hisobotlarga tegilmaydi.
create table if not exists notifies (
    id             bigserial primary key,
    name           varchar(80)  not null,
    -- Qabul qiluvchilar, vergul bilan: group:-100123, rol:KASSIR, user:5, kassa:2,
    -- karta_masul, click_chats, mehmonlar
    recipients     text         not null default '',
    -- Jadval: "every:2;from:9;to:21;off:0"  yoki  "times:09:00,13:00,18:30"
    schedule       varchar(200) not null default 'times:09:00',
    -- Hafta kunlari ISO (1=Du … 7=Ya), CSV; bo'sh = har kuni
    weekdays       varchar(20)  not null default '',
    -- Yuborilgan xabar necha daqiqadan keyin o'chirilsin (0 = o'chirilmasin)
    auto_delete_min int         not null default 0,
    template       text         not null default '',
    active         boolean      not null default true,
    -- Oxirgi yuborilgan daqiqa (yyyy-MM-ddTHH:mm) — bir daqiqada ikki marta ketmasin
    last_sent      varchar(20),
    last_error     varchar(300),
    created_at     timestamptz  not null default now()
);

-- Avtomatik o'chiriladigan xabarlar navbati (bot qayta ishga tushsa ham unutilmaydi)
create table if not exists pending_deletes (
    id          bigserial primary key,
    chat_id     bigint      not null,
    message_id  int         not null,
    delete_at   timestamptz not null,
    unique (chat_id, message_id)
);
