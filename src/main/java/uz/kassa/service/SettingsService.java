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
}
