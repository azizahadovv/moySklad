package uz.kassa.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.TgXabar;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TgXabarRepo extends JpaRepository<TgXabar, Long> {
    Optional<TgXabar> findByPhoneAndMsgId(String phone, long msgId);
    Optional<TgXabar> findByPhoneAndSourceBotAndMsgId(String phone, String sourceBot, long msgId);
    List<TgXabar> findByOrderByMsgAtDescIdDesc(Pageable p);
    List<TgXabar> findByPhoneOrderByMsgAtDescIdDesc(String phone, Pageable p);
    List<TgXabar> findByVerdictInOrderByMsgAtDescIdDesc(List<String> verdicts, Pageable p);
    long countByVerdict(String verdict);
    long countByMsgAtAfter(LocalDateTime t);
}
