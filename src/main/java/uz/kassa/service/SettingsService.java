package uz.kassa.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uz.kassa.domain.Setting;
import uz.kassa.repo.SettingRepo;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingRepo settingRepo;

    public Optional<String> get(String key) {
        return settingRepo.findById(key).map(Setting::getValue);
    }

    public void set(String key, String value) {
        settingRepo.save(Setting.builder().key(key).value(value).build());
    }

    /** Click hisobotidan uzilgan kartalar (vergulli id): hisobotda ko'rinmaydi, jarima yozilmaydi; MoySklad hisobi va balansi joyida. */
    public static final String CLICK_HIDDEN_KEY = "click.hidden";

    public java.util.Set<Long> clickHidden() {
        java.util.Set<Long> s = new java.util.HashSet<>();
        for (String x : get(CLICK_HIDDEN_KEY).orElse("").split(","))
            try { if (!x.isBlank()) s.add(Long.parseLong(x.trim())); } catch (NumberFormatException ignored) {}
        return s;
    }

    public void setClickHidden(long cardId, boolean hidden) {
        java.util.Set<Long> s = new java.util.TreeSet<>(clickHidden());
        if (hidden) s.add(cardId); else s.remove(cardId);
        set(CLICK_HIDDEN_KEY, String.join(",", s.stream().map(String::valueOf).toList()));
    }
}
