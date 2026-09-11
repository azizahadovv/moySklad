package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import uz.kassa.domain.OmborPozitsiya;
import java.util.List;

public interface OmborPozitsiyaRepo extends JpaRepository<OmborPozitsiya, OmborPozitsiya.Key> {
    List<OmborPozitsiya> findByHujjatId(Long hujjatId);
    @Modifying @Transactional
    @Query("delete from OmborPozitsiya p where p.hujjatId = :hujjatId")
    void deleteByHujjat(Long hujjatId);
}
