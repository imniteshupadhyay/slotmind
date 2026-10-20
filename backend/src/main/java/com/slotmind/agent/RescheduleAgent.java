package com.slotmind.agent;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.SuggestionStatus;
import com.slotmind.domain.TriggerReason;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.SlotRepository;
import com.slotmind.repo.SuggestionRepository;
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
    private static final EnumSet<AppointmentStatus> BOOKED =
            EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.RESCHEDULED, AppointmentStatus.RESCHEDULE_PENDING);

    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final SuggestionRepository suggestions;
    private final ReschedulingService rescheduling;
    private final AgentActivityLog activity;

    public RescheduleAgent(AppointmentRepository appointments, SlotRepository slots, SuggestionRepository suggestions,
                           ReschedulingService rescheduling, AgentActivityLog activity) {
        this.appointments = appointments;
        this.slots = slots;
        this.suggestions = suggestions;
        this.rescheduling = rescheduling;
        this.activity = activity;
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.ProviderWentOffline event) {
        List<AgentEvents.Superseded> batch = batch(needingPlan(appointments.findByProviderIdAndStatusIn(event.providerId(), BOOKED)),
                TriggerReason.PROVIDER_OFFLINE, event.invalidated());
        activity.info("Provider " + event.providerId() + " offline: " + batch.size() + " affected appointment(s)"
                + (batch.size() > 1 ? ", urgent first" : ""));
        rescheduleAll(batch);
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.SlotWasCancelled event) {
        List<AgentEvents.Superseded> batch = batch(needingPlan(appointments.findBySlotIdAndStatusIn(event.slotId(), BOOKED)),
                TriggerReason.SLOT_CANCELLED, event.invalidated());
        activity.info("Slot " + event.slotId() + " cancelled: " + batch.size() + " affected appointment(s)");
        rescheduleAll(batch);
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.SuggestionsSuperseded event) {
        event.items().forEach(item ->
                activity.info("Suggestion for " + item.appointmentId() + " superseded (slot taken) - re-proposing"));
        rescheduleAll(triaged(event.items()));
    }

    /** A RESCHEDULE_PENDING booking still waiting on a valid pending suggestion needs nothing new. */
    private List<Appointment> needingPlan(List<Appointment> booked) {
        return booked.stream()
                .filter(a -> a.getStatus() != AppointmentStatus.RESCHEDULE_PENDING
                        || suggestions.findByAppointmentIdAndStatus(a.getId(), SuggestionStatus.PENDING).isEmpty())
                .toList();
    }

    /**
     * One disruption, one triaged batch: the bookings it hit directly (with the disruption's trigger) and the
     * patients whose pending suggestion it invalidated (with the trigger chosen when it was rejected).
     */
    private List<AgentEvents.Superseded> batch(List<Appointment> direct, TriggerReason disruption,
                                               List<AgentEvents.Superseded> invalidated) {
        Map<String, TriggerReason> triggers = new HashMap<>();
        direct.forEach(a -> triggers.put(a.getId(), disruption));
        invalidated.forEach(i -> triggers.putIfAbsent(i.appointmentId(), i.trigger()));
        return triaged(triggers);
    }

    /** Re-proposals after an accept took their slot: same urgent-first order, each keeping its own trigger. */
    private List<AgentEvents.Superseded> triaged(List<AgentEvents.Superseded> items) {
        Map<String, TriggerReason> triggers = new HashMap<>();
        items.forEach(i -> triggers.putIfAbsent(i.appointmentId(), i.trigger()));
        return triaged(triggers);
    }

    private List<AgentEvents.Superseded> triaged(Map<String, TriggerReason> triggers) {
        return triage(appointments.findAllById(triggers.keySet())).stream()
                .map(id -> new AgentEvents.Superseded(id, triggers.get(id)))
                .toList();
    }

    /**
     * Batch order matters because each suggestion places a Hold on its slot: URGENT, then FOLLOW_UP, then ROUTINE,
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

    /** Sequential on purpose: each new suggestion Holds its slot before the next appointment is planned. */
    private void rescheduleAll(List<AgentEvents.Superseded> batch) {
        for (AgentEvents.Superseded item : batch) {
            try {
                rescheduling.propose(item.appointmentId(), item.trigger());
            } catch (Exception e) {
                // never drop silently: log and keep going with the rest of the batch
                log.error("Agent failed to reschedule {}", item.appointmentId(), e);
                activity.warn("Could not reschedule " + item.appointmentId() + ": " + e.getMessage());
            }
        }
    }
}
