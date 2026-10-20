package com.slotmind.service;

import com.slotmind.agent.AgentActivityLog;
import com.slotmind.domain.*;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.ProviderRepository;
import com.slotmind.repo.SlotRepository;
import com.slotmind.repo.SuggestionRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Addendum A seed data (times relative to now), plus a few extra OPEN slots so re-scheduling has
 * somewhere to go. Seeds on first start; POST /admin/reset re-seeds for a fresh demo.
 */
@Service
public class SeedService implements ApplicationRunner {

    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final AppointmentRepository appointments;
    private final SuggestionRepository suggestions;
    private final AgentActivityLog activity;

    public SeedService(ProviderRepository providers, SlotRepository slots, AppointmentRepository appointments,
                       SuggestionRepository suggestions, AgentActivityLog activity) {
        this.providers = providers;
        this.slots = slots;
        this.appointments = appointments;
        this.suggestions = suggestions;
        this.activity = activity;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (providers.count() == 0) reset();
    }

    @Transactional
    public void reset() {
        suggestions.deleteAllInBatch();
        appointments.deleteAllInBatch();
        slots.deleteAllInBatch();
        providers.deleteAllInBatch();
        activity.clear();

        providers.saveAll(List.of(
                new Provider("PRV-001", "Dr. Sarah Chen", Specialty.GENERAL, ProviderStatus.BUSY),
                new Provider("PRV-002", "Dr. James Okonkwo", Specialty.SPECIALIST, ProviderStatus.AVAILABLE),
                new Provider("PRV-003", "Dr. Priya Sharma", Specialty.PEDIATRIC, ProviderStatus.BUSY),
                new Provider("PRV-004", "Dr. Rahul Verma", Specialty.GENERAL, ProviderStatus.AVAILABLE),
                new Provider("PRV-005", "Dr. Ananya Iyer", Specialty.SPECIALIST, ProviderStatus.BUSY)));

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        slots.saveAll(List.of(
                new Slot("SLT-001", "PRV-001", now.plusMinutes(60), 30, SlotStatus.BOOKED),
                new Slot("SLT-002", "PRV-001", now.plusMinutes(120), 30, SlotStatus.BOOKED),
                new Slot("SLT-003", "PRV-003", now.plusMinutes(90), 45, SlotStatus.BOOKED),
                new Slot("SLT-004", "PRV-002", now.plusMinutes(180), 30, SlotStatus.OPEN),
                new Slot("SLT-005", "PRV-004", now.plusMinutes(150), 30, SlotStatus.OPEN),
                new Slot("SLT-006", "PRV-005", now.plusMinutes(240), 60, SlotStatus.BOOKED),
                // extra open capacity beyond Addendum A
                new Slot("SLT-007", "PRV-004", now.plusMinutes(210), 30, SlotStatus.OPEN),
                new Slot("SLT-008", "PRV-001", now.plusMinutes(330), 30, SlotStatus.OPEN),
                new Slot("SLT-009", "PRV-003", now.plusMinutes(270), 45, SlotStatus.OPEN),
                new Slot("SLT-010", "PRV-002", now.plusMinutes(300), 30, SlotStatus.OPEN),
                new Slot("SLT-011", "PRV-005", now.plusMinutes(360), 60, SlotStatus.OPEN),
                new Slot("SLT-012", "PRV-004", now.plusMinutes(1500), 30, SlotStatus.OPEN)));

        appointments.saveAll(List.of(
                new Appointment("APT-001", "John Martinez", AppointmentType.ROUTINE, "PRV-001", "SLT-001"),
                new Appointment("APT-002", "Emily Watson", AppointmentType.FOLLOW_UP, "PRV-001", "SLT-002"),
                new Appointment("APT-003", "Aisha Patel", AppointmentType.URGENT, "PRV-003", "SLT-003"),
                new Appointment("APT-004", "Robert Kim", AppointmentType.ROUTINE, "PRV-005", "SLT-006")));
        activity.info("Demo data seeded");
    }
}
