package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.kassa.domain.Category;
import java.util.List;

public interface CategoryRepo extends JpaRepository<Category, Long> {
    List<Category> findByActiveTrueOrderByIdAsc();
}
