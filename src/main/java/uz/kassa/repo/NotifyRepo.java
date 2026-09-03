package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Notify;

import java.util.List;

public interface NotifyRepo extends JpaRepository<Notify, Long> {
    List<Notify> findAllByOrderByIdAsc();
    List<Notify> findByActiveTrueOrderByIdAsc();
}
