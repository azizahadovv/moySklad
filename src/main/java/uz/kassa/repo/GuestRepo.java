package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Guest;

import java.util.List;

public interface GuestRepo extends JpaRepository<Guest, Long> {
    List<Guest> findAllByOrderByLastSeenDesc();
}
