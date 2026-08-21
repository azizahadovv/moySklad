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
    private final uz.kassa.repo.ClickAccountRepo clickRepo;

    public String owner(OwnerType t, Long id) {
        if (t == OwnerType.BUXGALTERIYA) return "Buxgalteriya";
        if (t == OwnerType.CLICK)
            return clickRepo.findById(id).map(uz.kassa.domain.ClickAccount::getName)
                    .orElse("Click #" + id);
        return kassaRepo.findById(id).map(Kassa::getName).orElse("Kassa #" + id);
    }
}
