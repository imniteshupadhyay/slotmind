package com.slotmind.agent;

import com.slotmind.agent.AgentEvents.PlanRequest;
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
import java.util.stream.Stream;

/**
 * The agentic loop. Separate listeners per trigger (they differ in how affected appointments are found),
 * one shared pipeline ({@link #rescheduleAll}) that runs the engine and queues suggestions for approval.
 * Runs after the triggering transaction commits, on its own executor, so the PATCH returns immediately.
 */
@Component
public class RescheduleAgent {

    private static final Logger log = LoggerFactory.getLogger(RescheduleAgent.class);

    private final AppointmentRepository appointments;
    private final SlotRepository slots;
    private final ReschedulingService rescheduling;
    private final AgentActivityLog activity;

    public RescheduleAgent(AppointmentRepository appointments, SlotRepository slots,
                           ReschedulingService rescheduling, AgentActivityLog activity) {
        this.appointments = appointments;
        this.slots = slots;
        this.rescheduling = rescheduling;
        this.activity = activity;
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.ProviderWentOffline event) {
        List<PlanRequest> batch = batch(
                needingPlan(appointments.findByProviderIdAndStatusIn(event.providerId(), AppointmentStatus.OCCUPYING_SLOT)),
                TriggerReason.PROVIDER_OFFLINE, event.invalidated());
        activity.info("Provider " + event.providerId() + " offline: " + batch.size() + " affected appointment(s)"
                + (batch.size() > 1 ? ", urgent first" : ""));
        rescheduleAll(batch);
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.SlotWasCancelled event) {
        List<PlanRequest> batch = batch(
                needingPlan(appointments.findBySlotIdAndStatusIn(event.slotId(), AppointmentStatus.OCCUPYING_SLOT)),
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
                .filter(a -> a.getStatus() != AppointmentStatus.RESCHEDULE_PENDING || !rescheduling.hasPending(a.getId()))
                .toList();
    }

    /**
     * One disruption, one triaged batch: the bookings it hit directly (with the disruption's trigger) and the
     * patients whose pending suggestion it invalidated (with the trigger chosen when it was rejected).
     * A patient in both keeps the disruption's trigger.
     */
    private List<PlanRequest> batch(List<Appointment> direct, TriggerReason disruption, List<PlanRequest> invalidated) {
        return triaged(Stream.concat(direct.stream().map(a -> new PlanRequest(a.getId(), disruption)), invalidated.stream())
                .toList());
    }

    /** Urgent first, each appointment once (its first request wins), each keeping its own trigger. */
    private List<PlanRequest> triaged(List<PlanRequest> requests) {
        Map<String, TriggerReason> triggers = new HashMap<>();
        requests.forEach(r -> triggers.putIfAbsent(r.appointmentId(), r.trigger()));
        return triage(appointments.findAllById(triggers.keySet())).stream()
                .map(id -> new PlanRequest(id, triggers.get(id)))
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
    private void rescheduleAll(List<PlanRequest> batch) {
        for (PlanRequest item : batch) {
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
