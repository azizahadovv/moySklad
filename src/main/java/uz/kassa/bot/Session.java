package uz.kassa.bot;

import java.util.HashMap;
import java.util.Map;

/** Foydalanuvchi dialog holati (FSM). Xotirada saqlanadi — restartda tozalanadi. */
public class Session {

    public enum State {
        IDLE,
        // Kassir: rasxod
        RX_MT, RX_AMT, RX_CAT, RX_CMT,
        // O'tkazma (kassir ham, buxgalter ham ishlatadi)
        TR_TGT, TR_MT, TR_AMT, TR_KIND, TR_DEBT, TR_CMT,
        // Buxgalter: o'z rasxodi
        BRX_MT, BRX_AMT, BRX_CAT, BRX_CMT,
        // Buxgalter: rad sabablari va qisman qabul
        RJ_RASXOD_REASON, RJ_SUB_REASON, SBP_NAQD, SBP_KLIK,
        // Admin oqimlari
        ADM_AU_PICK, ADM_AU_TGID, ADM_AU_NAME, ADM_AU_ROLE, ADM_AU_KASSA,
        ADM_AK_NAME, ADM_AK_MSID, ADM_AK_GROUP,
        ADM_IB_OWNER, ADM_IB_NAQD, ADM_IB_KLIK, ADM_IB_SANA,
        // Buxgalter/Admin: kassadan pul qabul qilish (summa kiritish)
        ADM_QB_SUM,
        // Click hisobiga boshlang'ich qoldiq: summa + sana
        ADM_CK_SUM, ADM_CK_SANA,
        // Tasdiqlangan rasxod summasini tahrirlash
        ADM_RXE_SUM,
        // Tugma nomini o'zgartirish
        ADM_LB_NAME,
        // MoySklad API kalitini kiritish
        ADM_MS_TOKEN,
        // 🤝 Kontragent (qarz daftari) oqimlari
        KG_SEARCH, KG_MN_NAME, KG_MN_INFO, KG_SUM, KG_IZOH,
        KG_AU_TGID, KG_AU_NAME, KG_RN_NAME
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
