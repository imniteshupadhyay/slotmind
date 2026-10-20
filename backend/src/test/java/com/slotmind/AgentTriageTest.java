package com.slotmind;

import com.slotmind.agent.AgentEvents;
import com.slotmind.agent.RescheduleAgent;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.AppointmentType;
import com.slotmind.domain.SuggestionStatus;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.SuggestionRepository;
import com.slotmind.service.BookingService;
import com.slotmind.service.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-slotmind.db?busy_timeout=10000",
        "slotmind.strategy.active=rule",
        "slotmind.llm.api-key="
})
class AgentTriageTest {

    @Autowired SeedService seed;
    @Autowired BookingService booking;
    @Autowired RescheduleAgent agent;
    @Autowired AppointmentRepository appointments;
    @Autowired SuggestionRepository suggestions;

    @Test
    void urgentPatientIsPlannedFirstAndGetsTheEarliestSlot() throws InterruptedException {
        seed.reset();
        // Booked last, latest original time, but urgent.
        String maria = booking.book("Maria Lopez", AppointmentType.URGENT, "PRV-001", "SLT-008").getId();

        var affected = appointments.findByProviderIdAndStatusIn("PRV-001",
                EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.RESCHEDULED));
        assertThat(agent.triage(affected)).containsExactly(maria, "APT-002", "APT-001"); // urgent, follow-up, routine

        agent.on(new AgentEvents.ProviderWentOffline("PRV-001")); // @Async proxy: completes on the agent thread
        long deadline = System.currentTimeMillis() + 15_000;
        while (suggestions.findByStatus(SuggestionStatus.PENDING).size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        var mariaSuggestion = suggestions.findByAppointmentIdAndStatus(maria, SuggestionStatus.PENDING);
        assertThat(mariaSuggestion).singleElement()
                .satisfies(s -> assertThat(s.getRecommendedSlotId()).isEqualTo("SLT-005")); // earliest GENERAL opening
    }
}
