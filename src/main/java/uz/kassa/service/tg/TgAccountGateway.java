package uz.kassa.service.tg;

/**
 * 📨 Akkaunt ulash darvozasi — bot (TDLib-siz asosiy kod) TDLib mijoziga shu interfeys orqali gaplashadi.
 * Amalga oshirish {@code src/tdlib/java} da (faqat «tdlib» profilida). Profil bo'lmasa bean yo'q — bot
 * «serverda yoqilmagan» deydi. Metodlar bloklaydi (TDLib javobini kutadi, ichki timeout bilan).
 */
public interface TgAccountGateway {

    enum Step { NEED_CODE, NEED_PASSWORD, CONNECTED, ERROR }

    /** Natija: keyingi qadam yoki xato (ERROR bo'lsa {@code error} to'ldiriladi). */
    record Result(Step step, String error) {
        public static Result of(Step s) { return new Result(s, null); }
        public static Result error(String e) { return new Result(Step.ERROR, e); }
        public boolean ok() { return step != Step.ERROR; }
    }

    /** Modul serverda tayyor mi (TG_API_ID/HASH bor, TDLib init bo'lgan). */
    boolean available();

    /** Telefon raqamiga login boshlash: TDLib kod so'raydi. Bloklaydi → NEED_CODE / CONNECTED / ERROR. */
    Result startLogin(String phone, long userId);

    /** Telegram'dan kelgan kodni yuborish. → NEED_PASSWORD / CONNECTED / ERROR. */
    Result submitCode(String phone, String code);

    /** 2FA (bulutli) parolni yuborish. → CONNECTED / ERROR. */
    Result submitPassword(String phone, String password);

    /** Yarim qolgan loginni bekor qilish (sessiya fayllari o'chiriladi). */
    void cancelLogin(String phone);

    /** Ulangan akkauntni uzish va sessiyani o'chirish (butunlay). */
    void disconnect(String phone);

    /** Mavjud (avtorizatsiya qilingan) sessiyani qayta ulash. Qaytadi: boshlandi mi. */
    boolean reconnect(String phone);
}
