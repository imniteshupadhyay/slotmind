package com.slotmind;

import com.slotmind.domain.*;
import com.slotmind.repo.SuggestionRepository;
import com.slotmind.service.BookingService;
import com.slotmind.service.ReschedulingService;
import com.slotmind.service.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** A disruption must re-plan patients whose pending suggestion it invalidates: no silent drops. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-slotmind.db?busy_timeout=10000",
        "slotmind.strategy.active=rule",
        "slotmind.llm.api-key="
})
class DisruptionInvalidationTest {

    @Autowired SeedService seed;
    @Autowired BookingService booking;
    @Autowired ReschedulingService rescheduling;
    @Autowired SuggestionRepository suggestions;

    @Test
    void suggestedProviderGoingOfflineRePlansThePatientKeepingTheTrigger() throws InterruptedException {
        seed.reset();
        hold("SLT-008"); // Chen's only open slot, so John's INITIAL suggestion goes to Dr. Verma
        RescheduleSuggestion first = rescheduling.propose("APT-001", TriggerReason.INITIAL).orElseThrow().suggestion();
        assertThat(first.getRecommendedSlotId()).isEqualTo("SLT-005");

        booking.changeProviderStatus("PRV-004", ProviderStatus.OFFLINE);

        RescheduleSuggestion next = awaitNewPending("APT-001", first.getId());
        assertThat(suggestions.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(SuggestionStatus.REJECTED);
        assertThat(next.getRecommendedSlotId()).isEqualTo("SLT-008"); // Verma is out; the Held Chen slot is shared
        assertThat(next.getTriggerReason()).isEqualTo(TriggerReason.INITIAL); // John's own provider is still working
    }

    @Test
    void cancellingTheSuggestedSlotRePlansThePatient() throws InterruptedException {
        seed.reset();
        RescheduleSuggestion first = rescheduling.propose("APT-001", TriggerReason.INITIAL).orElseThrow().suggestion();
        assertThat(first.getRecommendedSlotId()).isEqualTo("SLT-008"); // Chen's own nearest

        booking.changeSlotStatus("SLT-008", SlotStatus.CANCELLED);

        RescheduleSuggestion next = awaitNewPending("APT-001", first.getId());
        assertThat(next.getRecommendedSlotId()).isNotEqualTo("SLT-008");
        assertThat(next.getTriggerReason()).isEqualTo(TriggerReason.INITIAL); // John's own slot is untouched
    }

    @Test
    void patientWhoRejectedTheirSuggestionIsStillRePlannedWhenTheirProviderGoesOffline() throws InterruptedException {
        seed.reset();
        RescheduleSuggestion rejected = rescheduling.propose("APT-002", TriggerReason.INITIAL).orElseThrow().suggestion();
        booking.reject(rejected.getId()); // Emily: RESCHEDULE_PENDING with nothing pending

        booking.changeProviderStatus("PRV-001", ProviderStatus.OFFLINE);

        RescheduleSuggestion next = awaitNewPending("APT-002", rejected.getId());
        assertThat(next.getTriggerReason()).isEqualTo(TriggerReason.PROVIDER_OFFLINE);
    }

    /** A pending suggestion for another patient, placing a Hold on the slot. */
    private void hold(String slotId) {
        suggestions.save(new RescheduleSuggestion(Ids.next("SUG"), "APT-004", slotId, null,
                0.9, "held", TriggerReason.INITIAL, "rule"));
    }

    private RescheduleSuggestion awaitNewPending(String appointmentId, String previousId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var pending = suggestions.findByAppointmentIdAndStatus(appointmentId, SuggestionStatus.PENDING).stream()
                    .filter(s -> !s.getId().equals(previousId))
                    .findFirst();
            if (pending.isPresent()) return pending.get();
            Thread.sleep(100);
        }
        throw new AssertionError("No new pending suggestion for " + appointmentId);
    }
}
