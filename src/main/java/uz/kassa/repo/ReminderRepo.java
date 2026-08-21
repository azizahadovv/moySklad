package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Reminder;

import java.util.List;

public interface ReminderRepo extends JpaRepository<Reminder, Long> {

    List<Reminder> findByStatusOrderByDueDateAscIdAsc(Reminder.Status status);

    List<Reminder> findByAgentMsIdAndStatusOrderByDueDateAsc(String agentMsId, Reminder.Status status);
}
