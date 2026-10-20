package com.slotmind;

import com.slotmind.domain.Ids;
import com.slotmind.domain.RescheduleSuggestion;
import com.slotmind.domain.TriggerReason;
import com.slotmind.repo.SuggestionRepository;
import com.slotmind.service.ReschedulingService;
import com.slotmind.service.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-slotmind.db?busy_timeout=10000",
        "slotmind.strategy.active=rule",
        "slotmind.llm.api-key="
})
class SpecialtyHoldTest {

    @Autowired SeedService seed;
    @Autowired ReschedulingService rescheduling;
    @Autowired SuggestionRepository suggestions;

    @Test
    void heldSameSpecialtySlotBeatsAFreeSlotOfAnotherSpecialty() {
        seed.reset();
        // Every open GENERAL slot is Held by another patient; only SPECIALIST and PEDIATRIC slots are free.
        for (String general : new String[]{"SLT-005", "SLT-007", "SLT-008", "SLT-012"}) {
            suggestions.save(new RescheduleSuggestion(Ids.next("SUG"), "APT-002", general, null,
                    0.9, "held", TriggerReason.INITIAL, "rule"));
        }

        var proposal = rescheduling.propose("APT-001", TriggerReason.INITIAL); // John, GENERAL (Dr. Chen)

        assertThat(proposal).isPresent();
        assertThat(proposal.get().suggestion().getRecommendedSlotId()).isEqualTo("SLT-008"); // Chen's own, though Held
    }
}
