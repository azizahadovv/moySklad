-- ⚖️ Жарималар (2026-09-16, docs/JARIMA.md): xodim jarimalari reyestri.
-- tur:   KARTA (karta qoldig'i guruhga yuborilmadi) · KONTRAGENT (kontragent xato kiritildi) · OTGRUZKA (otgruzka kamchiligi)
-- holat: OGOH (birinchi holat — ogohlantirish, summa 0) · OCHIQ (jarima hisoblandi, to'lanmagan) · YOPIQ (admin yopdi) · BEKOR (admin bekor qildi)
-- asos/summa — SO'MDA. foiz — asosning necha foizi (sozlama jarima.foiz_*). tartib — shu xodimning shu turdagi nechanchi holati.
-- kalit — xodim kaliti: "u:<users.id>" yoki botga bog'lanmagan xodim uchun "n:<ism>" (ogohlantirish sanog'i shu bo'yicha).
CREATE TABLE IF NOT EXISTS jarima (
    id             BIGSERIAL PRIMARY KEY,
    tur            VARCHAR(12)  NOT NULL,
    holat          VARCHAR(8)   NOT NULL DEFAULT 'OCHIQ',
    user_id        BIGINT       REFERENCES users(id),
    kalit          VARCHAR(160) NOT NULL,
    xodim          VARCHAR(200) NOT NULL DEFAULT '',
    kassa_id       BIGINT       REFERENCES kassa(id),
    manba          VARCHAR(80)  NOT NULL DEFAULT '',
    manba_nomi     VARCHAR(400) NOT NULL DEFAULT '',
    asos           BIGINT       NOT NULL DEFAULT 0,
    foiz           DOUBLE PRECISION NOT NULL DEFAULT 1,
    summa          BIGINT       NOT NULL DEFAULT 0,
    sabab          TEXT         NOT NULL DEFAULT '',
    tartib         INT          NOT NULL DEFAULT 1,
    sana           DATE         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    xabar_at       TIMESTAMPTZ,
    kunlik_at      TIMESTAMPTZ,
    yopilgan_at    TIMESTAMPTZ,
    yopgan_user_id BIGINT,
    izoh           VARCHAR(400)
);
CREATE INDEX IF NOT EXISTS idx_jarima_sana  ON jarima(sana);
CREATE INDEX IF NOT EXISTS idx_jarima_user  ON jarima(user_id);
CREATE INDEX IF NOT EXISTS idx_jarima_kalit ON jarima(kalit, tur);
CREATE INDEX IF NOT EXISTS idx_jarima_manba ON jarima(tur, manba, created_at);
-- Sozlamalar (settings, kod default beradi): jarima.enabled=1, jarima.bazaviy=1000000 (so'm, kontragent xatosi asosi),
-- jarima.foiz_karta=1, jarima.foiz_kg=1, jarima.foiz_ot=1, jarima.ogoh_soni=1 (necha holat ogohlantirish),
-- jarima.xato_payt=TUZATILMADI|TOPILDI (kontragent/otgruzka xatosi qachon jarima bo'ladi), jarima.kun_vaqt=21:30, jarima.kun_sent (guard).
