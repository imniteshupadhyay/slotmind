package com.slotmind.agent;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.TriggerReason;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.service.ReschedulingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.List;

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
    private final ReschedulingService rescheduling;
    private final AgentActivityLog activity;

    public RescheduleAgent(AppointmentRepository appointments, ReschedulingService rescheduling, AgentActivityLog activity) {
        this.appointments = appointments;
        this.rescheduling = rescheduling;
        this.activity = activity;
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.ProviderWentOffline event) {
        List<String> affected = appointments.findByProviderIdAndStatusIn(event.providerId(), ACTIVE).stream()
                .map(Appointment::getId).toList();
        activity.info("Provider " + event.providerId() + " offline: " + affected.size() + " affected appointment(s)");
        rescheduleAll(affected, TriggerReason.PROVIDER_OFFLINE);
    }

    @Async("agentExecutor")
    @TransactionalEventListener(fallbackExecution = true)
    public void on(AgentEvents.SlotWasCancelled event) {
        List<String> affected = appointments.findBySlotIdAndStatusIn(event.slotId(), ACTIVE).stream()
                .map(Appointment::getId).toList();
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
