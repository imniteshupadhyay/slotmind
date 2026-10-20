package com.slotmind.agent;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.TriggerReason;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.SlotRepository;
import com.slotmind.service.ReschedulingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.*;

/**
 * The agentic loop. Separate listeners per trigger (they differ in how affected appointments are found),
 * one shared pipeline ({@link #rescheduleAll}) that runs the engine and queues suggestions for approval.
 * Runs after the triggering transaction commits, on its own executor, so the PATCH returns immediately.
 */
@Component
public class RescheduleAgent {

    private static final Logger log = LoggerFactory.getLogger(RescheduleAgent.class);
    private static final EnumSet<AppointmentStatus> ACTIVE = EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.RESCHEDULED);

    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final ReschedulingService rescheduling;
    private final AgentActivityLog activity;

    public RescheduleAgent(AppointmentRepository appointments, SlotRepository slots, ReschedulingService rescheduling,
                           AgentActivityLog activity) {
        this.appointments = appointments;
        this.slots = slots;
        this.rescheduling = rescheduling;
        this.activity = activity;
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.ProviderWentOffline event) {
        List<String> affected = triage(appointments.findByProviderIdAndStatusIn(event.providerId(), ACTIVE));
        activity.info("Provider " + event.providerId() + " offline: " + affected.size() + " affected appointment(s)"
                + (affected.size() > 1 ? ", urgent first" : ""));
        rescheduleAll(affected, TriggerReason.PROVIDER_OFFLINE);
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.SlotWasCancelled event) {
        List<String> affected = triage(appointments.findBySlotIdAndStatusIn(event.slotId(), ACTIVE));
        activity.info("Slot " + event.slotId() + " cancelled: " + affected.size() + " affected appointment(s)");
        rescheduleAll(affected, TriggerReason.SLOT_CANCELLED);
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.SuggestionsSuperseded event) {
        for (AgentEvents.Superseded item : event.items()) {
            activity.info("Suggestion for " + item.appointmentId() + " superseded (slot taken) - re-proposing");
            rescheduleAll(List.of(item.appointmentId()), item.trigger());
        }
    }

    /**
     * Batch order matters because each suggestion soft-reserves its slot: URGENT, then FOLLOW_UP, then ROUTINE,
     * ties broken by the earliest original appointment time.
     */
    public List<String> triage(List<Appointment> affected) {
        Map<String, LocalDateTime> startBySlot = new HashMap<>();
        slots.findAllById(affected.stream().map(Appointment::getSlotId).toList())
                .forEach(s -> startBySlot.put(s.getId(), s.getStartTime()));
        return affected.stream()
                .sorted(Comparator.comparingInt((Appointment a) -> a.getAppointmentType().priority())
                        .thenComparing(a -> startBySlot.getOrDefault(a.getSlotId(), LocalDateTime.MAX)))
                .map(Appointment::getId)
                .toList();
    }

    /** Sequential on purpose: each new suggestion soft-reserves its slot before the next appointment is planned. */
    private void rescheduleAll(List<String> appointmentIds, TriggerReason trigger) {
        for (String id : appointmentIds) {
            try {
                rescheduling.propose(id, trigger);
            } catch (Exception e) {
                // never drop silently: log and keep going with the rest of the batch
                log.error("Agent failed to reschedule {}", id, e);
                activity.warn("Could not reschedule " + id + ": " + e.getMessage());
            }
        }
    }
}
