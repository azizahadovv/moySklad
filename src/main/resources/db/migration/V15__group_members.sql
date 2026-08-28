-- Guruh a'zolari registri ({hamma} shabloni uchun): Bot API guruhning to'liq
-- a'zolar ro'yxatini BERMAYDI, shuning uchun bot ko'rgan odamlarni o'zi yig'adi —
-- guruhda yozgan yoki guruhga qo'shilgan har bir odam shu jadvalga tushadi,
-- chiqib ketgani o'chiriladi.
CREATE TABLE group_members (
    id         BIGSERIAL PRIMARY KEY,
    chat_id    BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    username   VARCHAR(64),
    first_name VARCHAR(128),
    last_seen  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_members UNIQUE (chat_id, user_id)
);
