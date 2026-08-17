package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Setting;

public interface SettingRepo extends JpaRepository<Setting, String> {
}
