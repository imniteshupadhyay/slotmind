package com.slotmind;

import com.slotmind.domain.*;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.SlotRepository;
import com.slotmind.repo.SuggestionRepository;
import com.slotmind.service.BookingService;
import com.slotmind.service.SeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-slotmind.db?busy_timeout=10000",
        "slotmind.strategy.active=rule",
        "slotmind.llm.api-key="
})
class AcceptConcurrencyTest {

    @Autowired SeedService seed;
    @Autowired BookingService booking;
    @Autowired AppointmentRepository appointments;
    @Autowired SlotRepository slots;
    @Autowired SuggestionRepository suggestions;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        seed.reset();
    }

    @Test
    void twoStaffAcceptingTheSameSlotOnlyOneWins() throws Exception {
        // Two different patients were both offered SLT-005.
        tx.executeWithoutResult(s -> {
            for (String id : List.of("APT-001", "APT-002")) {
                appointments.findById(id).orElseThrow().markReschedulePending();
            }
            suggestions.save(new RescheduleSuggestion("SUG-A", "APT-001", "SLT-005", "PRV-004", 0.8, "a", TriggerReason.PROVIDER_OFFLINE, "rule"));
            suggestions.save(new RescheduleSuggestion("SUG-B", "APT-002", "SLT-005", "PRV-004", 0.8, "b", TriggerReason.PROVIDER_OFFLINE, "rule"));
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = List.of("SUG-A", "SUG-B").stream().map(id -> pool.submit(() -> {
            start.await();
            try {
                booking.accept(id);
                wins.incrementAndGet();
            } catch (RuntimeException expected) {
                // loser: the single connection runs the accepts in turn, so the state checks (slot booked,
                // suggestion superseded) stop it; @Version is proven separately below
            }
            return null;
        })).<Future<?>>map(f -> f).toList();
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(wins.get()).isEqualTo(1);
        long holders = appointments.findAll().stream().filter(a -> "SLT-005".equals(a.getSlotId())).count();
        assertThat(holders).isEqualTo(1);
        assertThat(slots.findById("SLT-005").orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    void acceptMovesAppointmentAndFreesOldSlotAtomically() {
        tx.executeWithoutResult(s -> {
            appointments.findById("APT-001").orElseThrow().markReschedulePending();
            suggestions.save(new RescheduleSuggestion("SUG-C", "APT-001", "SLT-005", "PRV-004", 0.8, "c", TriggerReason.PROVIDER_OFFLINE, "rule"));
        });

        booking.accept("SUG-C");

        Appointment a = appointments.findById("APT-001").orElseThrow();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.RESCHEDULED);
        assertThat(a.getSlotId()).isEqualTo("SLT-005");
        assertThat(a.getProviderId()).isEqualTo("PRV-004");
        assertThat(slots.findById("SLT-001").orElseThrow().getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(slots.findById("SLT-005").orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    /*
     * The pool has one connection, so two transactions can never interleave. A JDBC write on that same
     * connection plays the other staff member: it changes the row and bumps its version after we loaded it,
     * which is what a second connection would do if the pool ever grew.
     */

    @Test
    void staleSlotWriteIsRejectedByItsVersion() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Slot slot = slots.findById("SLT-005").orElseThrow();
            jdbc.update("update slots set status = 'BOOKED', version = version + 1 where id = ?", "SLT-005");
            slot.book(); // our stale copy still says OPEN, so only the version check can stop this write
        })).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void acceptFailsWhenItsProviderGoesOfflineBeforeCommit() {
        tx.executeWithoutResult(s -> {
            appointments.findById("APT-001").orElseThrow().markReschedulePending();
            suggestions.save(new RescheduleSuggestion("SUG-D", "APT-001", "SLT-005", "PRV-004", 0.8, "d", TriggerReason.PROVIDER_OFFLINE, "rule"));
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            booking.accept("SUG-D"); // passes the isBookable() check: PRV-004 is still available here
            jdbc.update("update providers set status = 'OFFLINE', version = version + 1 where id = ?", "PRV-004");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(appointments.findById("APT-001").orElseThrow().getSlotId()).isEqualTo("SLT-001");
        assertThat(slots.findById("SLT-005").orElseThrow().getStatus()).isEqualTo(SlotStatus.OPEN);
    }
}
