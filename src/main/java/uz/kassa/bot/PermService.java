package uz.kassa.bot;

import org.springframework.stereotype.Component;
import uz.kassa.domain.AppUser;
import uz.kassa.domain.Role;
import uz.kassa.repo.SettingRepo;
import uz.kassa.service.SettingsService;

import java.util.HashMap;
import java.util.Map;

/**
 * Bo'lim huquqlari — USER va OTDEL (kassa) kesimida:
 *   1) user uchun aniq belgilangan bo'lsa (✅ ruxsat / 🚫 taqiq) — o'sha amal qiladi;
 *   2) bo'lmasa, useri biriktirilgan KASSA uchun belgilangani;
 *   3) bo'lmasa — umumiy holat (LabelService: bo'lim global o'chirilganmi).
 * SUPERADMIN uchun cheklov ishlamaydi.
 * Saqlash: settings jadvalida "perm.user.<id>" / "perm.kassa.<id>" kalitlari,
 * qiymati qatorlar: "<kanonik nom>=0|1".
 */
@Component
public class PermService {

    private final SettingsService settings;
    private final SettingRepo settingRepo;
    private final LabelService labelSvc;

    private volatile Map<Long, Map<String, Boolean>> userOv = Map.of();
    private volatile Map<Long, Map<String, Boolean>> kassaOv = Map.of();
    /** Rol kesimida: "perm.role.<ROL>" — qatorlar canonical=0|1 (web sxema muharriri). */
    private volatile Map<Role, Map<String, Boolean>> roleOv = Map.of();

    public PermService(SettingsService settings, SettingRepo settingRepo, LabelService labelSvc) {
        this.settings = settings;
        this.settingRepo = settingRepo;
        this.labelSvc = labelSvc;
    }

    @jakarta.annotation.PostConstruct
    public void reload() {
        Map<Long, Map<String, Boolean>> u = new HashMap<>();
        Map<Long, Map<String, Boolean>> k = new HashMap<>();
        Map<Role, Map<String, Boolean>> r = new HashMap<>();
        for (uz.kassa.domain.Setting st : settingRepo.findAll()) {
            String key = st.getKey();
            if (key.startsWith("perm.user.")) put(u, key.substring(10), st.getValue());
            else if (key.startsWith("perm.kassa.")) put(k, key.substring(11), st.getValue());
            else if (key.startsWith("perm.role.")) {
                try {
                    Map<String, Boolean> m = parse(st.getValue());
                    if (!m.isEmpty()) r.put(Role.valueOf(key.substring(10)), m);
                } catch (IllegalArgumentException ignored) { /* noma'lum rol */ }
            }
        }
        userOv = u;
        kassaOv = k;
        roleOv = r;
    }

    private void put(Map<Long, Map<String, Boolean>> into, String idS, String value) {
        long id;
        try { id = Long.parseLong(idS); } catch (NumberFormatException e) { return; }
        Map<String, Boolean> m = parse(value);
        if (!m.isEmpty()) into.put(id, m);
    }

    private static Map<String, Boolean> parse(String value) {
        Map<String, Boolean> m = new HashMap<>();
        for (String line : (value == null ? "" : value).split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            m.put(line.substring(0, eq), line.substring(eq + 1).equals("1"));
        }
        return m;
    }

    /** Rol uchun belgilangan holat: null — belgilanmagan, true — ruxsat, false — taqiq. */
    public Boolean roleOverride(Role role, String canonical) {
        Map<String, Boolean> m = roleOv.get(role);
        return m == null ? null : m.get(canonical);
    }

    /** Shu bo'lim uchun aniq belgilangan foydalanuvchilar soni (istisnolar). */
    public int userExceptions(String canonical) {
        int n = 0;
        for (Map<String, Boolean> m : userOv.values()) if (m.containsKey(canonical)) n++;
        return n;
    }

    /** Rol darajasida belgilash: state=null — olib tashlash. */
    public void setRole(Role role, String canonical, Boolean state) {
        Map<String, Boolean> m = new HashMap<>();
        Map<String, Boolean> cur = roleOv.get(role);
        if (cur != null) m.putAll(cur);
        if (state == null) m.remove(canonical); else m.put(canonical, state);
        StringBuilder sb = new StringBuilder();
        m.forEach((c, v) -> sb.append(c).append('=').append(v ? "1" : "0").append('\n'));
        settings.set("perm.role." + role.name(), sb.toString());
        reload();
    }

    /** null — belgilanmagan (meros), true — ruxsat, false — taqiq. */
    public Boolean userOverride(long userId, String canonical) {
        Map<String, Boolean> m = userOv.get(userId);
        return m == null ? null : m.get(canonical);
    }

    public Boolean kassaOverride(long kassaId, String canonical) {
        Map<String, Boolean> m = kassaOv.get(kassaId);
        return m == null ? null : m.get(canonical);
    }

    /** SuperAdmin shu user uchun kamida bitta bo'lim huquqini aniq belgilaganmi. */
    public boolean userConfigured(long userId) { return userOv.containsKey(userId); }

    /** SuperAdmin shu kassa uchun kamida bitta bo'lim huquqini aniq belgilaganmi. */
    public boolean kassaConfigured(long kassaId) { return kassaOv.containsKey(kassaId); }

    /**
     * Foydalanuvchi shu bo'limni ko'rishi/ishlatishi mumkinmi.
     * Agar SuperAdmin shu user (yoki uning kassasi) uchun huquqlar bo'limini umuman
     * sozlagan bo'lsa — shu darajada RO'YXATDAN TASHQARI (belgilanmagan) bo'limlar
     * endi "meros" emas, balki TAQIQLANGAN hisoblanadi: faqat aniq ✅ berilganlar ko'rinadi.
     * Hech narsa sozlanmagan (yangi/nazoratsiz) user/kassa uchun — eski xatti-harakat:
     * umumiy holat (global yoqilgan/o'chirilgan) bo'yicha ochiq.
     */
    public boolean visible(AppUser u, String canonical) {
        if (u.getRole() == Role.SUPERADMIN) return true;
        Map<String, Boolean> um = userOv.get(u.getId());
        if (um != null) {
            Boolean o = um.get(canonical);
            return o != null && o;
        }
        if (u.getKassaId() != null) {
            Map<String, Boolean> km = kassaOv.get(u.getKassaId());
            if (km != null) {
                Boolean o = km.get(canonical);
                return o != null && o;
            }
        }
        // 3) rol darajasi (web sxema muharriri): faqat shu bo'lim uchun belgilangani
        Boolean ro = roleOverride(u.getRole(), canonical);
        if (ro != null) return ro;
        return !labelSvc.isHidden(canonical);
    }

    /**
     * Belgilash: state=null — merosga qaytarish, true — ruxsat, false — taqiq.
     * subj: "user" yoki "kassa".
     */
    public void set(String subj, long id, String canonical, Boolean state) {
        String key = "perm." + subj + "." + id;
        Map<String, Boolean> m = new HashMap<>();
        Map<Long, Map<String, Boolean>> src = subj.equals("user") ? userOv : kassaOv;
        Map<String, Boolean> cur = src.get(id);
        if (cur != null) m.putAll(cur);
        if (state == null) m.remove(canonical);
        else m.put(canonical, state);
        StringBuilder sb = new StringBuilder();
        m.forEach((c, v) -> sb.append(c).append('=').append(v ? "1" : "0").append('\n'));
        settings.set(key, sb.toString());
        reload();
    }
}
