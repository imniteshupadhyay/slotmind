package com.slotmind;

import com.slotmind.domain.Ids;
import com.slotmind.domain.RescheduleSuggestion;
import com.slotmind.domain.SuggestionStatus;
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
class ManualSuggestDedupeTest {

    @Autowired SeedService seed;
    @Autowired ReschedulingService rescheduling;
    @Autowired SuggestionRepository suggestions;

    @Test
    void manualSuggestReturnsThePendingSuggestionWhateverItsTrigger() {
        seed.reset();
        RescheduleSuggestion agents = suggestions.save(new RescheduleSuggestion(Ids.next("SUG"), "APT-001", "SLT-008",
                null, 0.9, "agent proposal", TriggerReason.PROVIDER_OFFLINE, "rule"));

        var proposal = rescheduling.suggestManually("APT-001");

        assertThat(proposal).isPresent();
        assertThat(proposal.get().created()).isFalse();
        assertThat(proposal.get().suggestion().getId()).isEqualTo(agents.getId());
        assertThat(suggestions.findByAppointmentIdAndStatus("APT-001", SuggestionStatus.PENDING)).hasSize(1);
    }

    @Test
    void agentDedupeStaysPerTrigger() {
        seed.reset();
        RescheduleSuggestion agents = suggestions.save(new RescheduleSuggestion(Ids.next("SUG"), "APT-001", "SLT-008",
                null, 0.9, "agent proposal", TriggerReason.PROVIDER_OFFLINE, "rule"));

        var proposal = rescheduling.propose("APT-001", TriggerReason.PROVIDER_OFFLINE);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().created()).isFalse();
        assertThat(proposal.get().suggestion().getId()).isEqualTo(agents.getId());
    }

    @Test
    void agentProposalWithANewTriggerSupersedesTheOlderPendingSuggestion() {
        seed.reset();
        RescheduleSuggestion manual = suggestions.save(new RescheduleSuggestion(Ids.next("SUG"), "APT-001", "SLT-008",
                null, 0.9, "manual proposal", TriggerReason.INITIAL, "rule"));

        var proposal = rescheduling.propose("APT-001", TriggerReason.PROVIDER_OFFLINE);

        assertThat(proposal).isPresent();
        assertThat(proposal.get().created()).isTrue();
        assertThat(proposal.get().suggestion().getTriggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
        assertThat(suggestions.findById(manual.getId()).orElseThrow().getStatus()).isEqualTo(SuggestionStatus.REJECTED);
        assertThat(suggestions.findByAppointmentIdAndStatus("APT-001", SuggestionStatus.PENDING)).hasSize(1);
    }
}
