package uz.kassa.service.tg;

import java.time.Instant;
import java.util.List;

/**
 * 📨 Akkaunt ulash darvozasi — bot shu interfeys orqali userbot mijoziga gaplashadi. Yagona amalga
 * oshirish {@link TgReaderHttpGateway} (doim kompilyatsiya bo'ladi va bean sifatida ro'yxatdan o'tadi,
 * {@code tg-reader} Python/Telethon xizmatiga HTTP orqali). Eski {@code src/tdlib/java}dagi TDLib (Java)
 * mijozi — faqat «tdlib» Maven profilida, standart build'da ISHLATILMAYDI (Dockerfile {@code ARG TDLIB}
 * bo'sh, sabab: mvn.mchv.eu tarmoqdan yopiq). Metodlar bloklaydi (userbot javobini kutadi, ichki timeout bilan).
 * <p><b>Login — QR KOD orqali</b> (2026-09-15, telefon+kod-matn usuli ATAYLAB olib tashlandi): Telegram
 * kodni xabar sifatida yozishni fishing-qarshi himoya deb hisoblab, kirishni SERVER darajasida bloklaydi
 * (client PHONE_CODE_EXPIRED ko'radi, kod to'g'ri kiritilgan bo'lsa ham — TDLib yoki Telethondan qat'i
 * nazar). QR skanerlash (xuddi Telegram Desktop/Web «Qurilma ulash» kabi) bu bloklashni tetiklamaydi.
 */
public interface TgAccountGateway {

    enum Step { QR, NEED_PASSWORD, CONNECTED, ERROR }

    /** Natija: QR bo'lsa {@code qrPngBase64} to'ldirilgan, CONNECTED bo'lsa {@code phone}, ERROR bo'lsa {@code error}. */
    record Result(Step step, int gen, String qrPngBase64, String phone, String error) {
        public static Result error(String e) { return new Result(Step.ERROR, 0, null, null, e); }
        public boolean ok() { return step != Step.ERROR; }
    }

    /** Ulanishni tekshirish natijasi (test xabar «Saqlangan xabarlar»ga yuborilgandan keyin). */
    record TestResult(boolean ok, String message) {}

    /** Hozir jarayonda (QR ko'rsatilgan, hali skanerlanmagan/parol kutilmoqda) login — «chala qolgan» holat uchun. Telefon ulanmaguncha noma'lum. */
    record PendingLogin(long userId, Instant startedAt) {}

    /** Modul serverda tayyor mi (TG_API_ID/HASH bor). */
    boolean available();

    /** QR-login boshlash: {@code userId} — bu akkaunt bog'lanadigan xodim (odatda o'zi). Bloklaydi → QR (rasm bilan) / ERROR. */
    Result startQrLogin(long userId);

    /** Holatni so'rash (bot muntazam chaqiradi): QR hali kutilmoqda (yangi tokenda {@code gen} oshadi, rasm yangilanadi), NEED_PASSWORD, CONNECTED yoki ERROR. */
    Result pollQr(long userId);

    /** 2FA (bulutli) parolni yuborish. → CONNECTED / ERROR. */
    Result submitPassword(long userId, String password);

    /** Yarim qolgan QR-loginni bekor qilish (vaqtinchalik sessiya o'chiriladi). */
    void cancelLogin(long userId);

    /** Ulanishni TO'XTATISH (pauza) — userbot mijozi yopiladi, sessiya fayllari SAQLANADI. {@link #reconnect} bilan qaytadi. */
    void pause(String phone);

    /** Ulangan akkauntni uzish va sessiyani BUTUNLAY o'chirish — qayta ulash uchun to'liq QR-login kerak bo'ladi. */
    void disconnect(String phone);

    /** Mavjud (avtorizatsiya qilingan, pauzadagi yoki avto o'chgan) sessiyani qayta ulash. Qaytadi: boshlandi mi. */
    boolean reconnect(String phone);

    /** Ulanishni jonli tekshirish: akkauntning o'z «Saqlangan xabarlar»iga qisqa test xabar yuboradi. */
    TestResult test(String phone);

    /** Hozir jarayonda (hali CONNECTED/ERROR bo'lmagan) login urinishlari — admin uchun «chala qolgan» ro'yxati. */
    List<PendingLogin> pendingLogins();
}
