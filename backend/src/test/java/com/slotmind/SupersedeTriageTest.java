package com.slotmind;

import com.slotmind.domain.*;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.SuggestionRepository;
import com.slotmind.service.BookingService;
import com.slotmind.service.SeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** When one accept supersedes several patients' suggestions, they are re-proposed urgent first. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-slotmind.db?busy_timeout=10000",
        "slotmind.strategy.active=rule",
        "slotmind.llm.api-key="
})
class SupersedeTriageTest {

    @Autowired SeedService seed;
    @Autowired BookingService booking;
    @Autowired AppointmentRepository appointments;
    @Autowired SuggestionRepository suggestions;

    @Test
    void acceptThatSupersedesSeveralPatientsRePlansTheUrgentOneFirst() throws InterruptedException {
        seed.reset();
        // Maria: urgent, with Dr. Chen (like John), booked into a late extra slot.
        Slot late = booking.createSlot("PRV-001", LocalDateTime.now().plusHours(12), 30);
        String maria = booking.book("Maria Lopez", AppointmentType.URGENT, "PRV-001", late.getId()).getId();

        // Three patients were all offered Dr. Verma's SLT-005; the routine one was queued first.
        pending("APT-001", "SLT-005"); // John, routine
        pending(maria, "SLT-005");
        RescheduleSuggestion emily = pending("APT-002", "SLT-005"); // Emily, follow-up

        booking.accept(emily.getId()); // frees Chen's SLT-002, supersedes John and Maria

        RescheduleSuggestion mariaNext = awaitPending(maria);
        RescheduleSuggestion johnNext = awaitPending("APT-001");
        assertThat(mariaNext.getRecommendedSlotId()).isEqualTo("SLT-002"); // Chen's earliest opening goes to urgent
        assertThat(johnNext.getRecommendedSlotId()).isEqualTo("SLT-008"); // routine gets the next one
    }

    /** A pending suggestion offering this patient the slot (placing a Hold), as the agent would leave it. */
    private RescheduleSuggestion pending(String appointmentId, String slotId) {
        Appointment appt = appointments.findById(appointmentId).orElseThrow();
        appt.markReschedulePending();
        appointments.save(appt);
        return suggestions.save(new RescheduleSuggestion(Ids.next("SUG"), appointmentId, slotId, "PRV-004",
                0.8, "offered", TriggerReason.INITIAL, "rule"));
    }

    private RescheduleSuggestion awaitPending(String appointmentId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            var pending = suggestions.findByAppointmentIdAndStatus(appointmentId, SuggestionStatus.PENDING);
            if (!pending.isEmpty()) return pending.get(0);
            Thread.sleep(100);
        }
        throw new AssertionError("No new pending suggestion for " + appointmentId);
    }
}
