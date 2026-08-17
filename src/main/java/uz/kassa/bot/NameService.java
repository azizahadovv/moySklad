package uz.kassa.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uz.kassa.domain.Kassa;
import uz.kassa.domain.OwnerType;
import uz.kassa.repo.KassaRepo;

@Component
@RequiredArgsConstructor
public class NameService {
    private final KassaRepo kassaRepo;

    public String owner(OwnerType t, Long id) {
        if (t == OwnerType.BUXGALTERIYA) return "Buxgalteriya";
        return kassaRepo.findById(id).map(Kassa::getName).orElse("Kassa #" + id);
    }
}
