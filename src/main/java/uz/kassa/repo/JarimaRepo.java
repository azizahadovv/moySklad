package uz.kassa.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.kassa.domain.Jarima;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface JarimaRepo extends JpaRepository<Jarima, Long> {

    List<Jarima> findBySanaBetweenOrderByIdDesc(LocalDate from, LocalDate to);

    List<Jarima> findBySanaOrderByIdAsc(LocalDate sana);

    List<Jarima> findByUserIdAndSanaBetweenOrderByIdDesc(Long userId, LocalDate from, LocalDate to);

    List<Jarima> findByUserIdAndHolat(Long userId, Jarima.Holat holat);

    /** Shu xodim (kalit) shu turda nechta holat (ogohlantirish + jarima) olgan. */
    long countByKalitAndTur(String kalit, Jarima.Tur tur);

    /** Tartib uchun: BEKOR qilinganlar sanalmaydi (2026-09-18) — noto'g'ri yozilgan 1-holat bekor qilinsa keyingisi yana 1-holat. */
    long countByKalitAndTurAndHolatNot(String kalit, Jarima.Tur tur, Jarima.Holat holat);

    /** Shu manba bo'yicha shu epizodda (after dan keyin) yozuv bormi — takror yozmaslik. */
    boolean existsByTurAndManbaAndCreatedAtAfter(Jarima.Tur tur, String manba, Instant after);

    boolean existsByTurAndManbaAndSana(Jarima.Tur tur, String manba, LocalDate sana);

    long countByHolat(Jarima.Holat holat);

    @Query("select coalesce(sum(j.summa), 0) from Jarima j where j.holat = :h")
    long sumByHolat(@Param("h") Jarima.Holat h);

    @Query("select coalesce(sum(j.summa), 0) from Jarima j where j.holat = :h and j.userId = :uid")
    long sumByHolatAndUser(@Param("h") Jarima.Holat h, @Param("uid") Long userId);
}
