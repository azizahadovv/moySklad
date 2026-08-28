package uz.kassa.bot;

import java.util.HashMap;
import java.util.Map;

/** Foydalanuvchi dialog holati (FSM). Xotirada saqlanadi — restartda tozalanadi. */
public class Session {

    public enum State {
        IDLE,
        // O'tkazma (kassir ham, buxgalter ham ishlatadi)
        TR_TGT, TR_MT, TR_AMT, TR_KIND, TR_DEBT, TR_CMT,
        // Buxgalter: rad sabablari va qisman qabul
        RJ_SUB_REASON, SBP_NAQD, SBP_KLIK,
        // Admin oqimlari
        ADM_AU_PICK, ADM_AU_TGID, ADM_AU_NAME, ADM_AU_ROLE, ADM_AU_KASSA,
        ADM_AK_NAME, ADM_AK_MSID, ADM_AK_GROUP,
        ADM_IB_OWNER, ADM_IB_NAQD, ADM_IB_KLIK, ADM_IB_SANA,
        // Korrektirovka: otdel (kassa/buxgalteriya) tanlash -> pul turi -> summa -> sabab -> sana -> soat
        ADM_KR_OWNER, ADM_KR_MT, ADM_KR_SUM, ADM_KR_IZOH, ADM_KR_SANA, ADM_KR_VAQT,
        // Buxgalter/Admin: kassadan pul qabul qilish (summa kiritish)
        ADM_QB_SUM,
        // Click hisobiga boshlang'ich qoldiq: summa + sana
        ADM_CK_SUM, ADM_CK_SANA,
        // Tugma nomini o'zgartirish
        ADM_LB_NAME,
        // MoySklad API kalitini kiritish
        ADM_MS_TOKEN,
        // Kassa/Click hisobi nomini qo'lda o'zgartirish
        ADM_NM_NAME,
        ADM_CG_ID,
        // Click hisobot ostiga qo'shiladigan matn (@mention va h.k.)
        ADM_CG_FOOTER,
        // Ledger boshlanish sanasini kiritish
        ADM_LS_DATE,
        // 🤝 Kontragent (qarz daftari) oqimlari
        KG_SEARCH, KG_MN_NAME, KG_MN_INFO, KG_SUM, KG_IZOH,
        KG_AU_TGID, KG_AU_NAME, KG_RN_NAME, KG_PAY_AMOUNT
    }

    public State state = State.IDLE;
    public final Map<String, Object> data = new HashMap<>();

    public void reset() {
        state = State.IDLE;
        data.clear();
    }

    public long getLong(String key) { return ((Number) data.get(key)).longValue(); }
    public String getStr(String key) { Object v = data.get(key); return v == null ? null : v.toString(); }
}
