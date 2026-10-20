package com.slotmind.service;

import com.slotmind.agent.AgentActivityLog;
import com.slotmind.agent.AgentEvents;
import com.slotmind.agent.AgentEvents.PlanRequest;
import com.slotmind.domain.*;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.ProviderRepository;
import com.slotmind.repo.SlotRepository;
import com.slotmind.repo.SuggestionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** All state-changing commands. Each public method is one transaction. */
@Service
public class BookingService {

    private final AppointmentRepository appointments;
    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final SuggestionRepository suggestions;
    private final ApplicationEventPublisher events;
    private final AgentActivityLog activity;

    public BookingService(AppointmentRepository appointments, ProviderRepository providers, SlotRepository slots,
                          SuggestionRepository suggestions, ApplicationEventPublisher events, AgentActivityLog activity) {
        this.appointments = appointments;
        this.providers = providers;
        this.slots = slots;
        this.suggestions = suggestions;
        this.events = events;
        this.activity = activity;
    }

    @Transactional
    public Appointment book(String patientName, AppointmentType type, String providerId, String slotId) {
        Provider provider = providers.require(providerId);
        if (!provider.isBookable()) throw new DomainException(provider.getName() + " is offline");
        Slot slot = slots.require(slotId);
        if (!slot.getProviderId().equals(providerId)) {
            throw new DomainException("Slot " + slotId + " does not belong to provider " + providerId);
        }
        slot.book(); // throws if not OPEN; @Version guards concurrent bookings
        return appointments.save(new Appointment(Ids.next("APT"), patientName, type, providerId, slotId));
    }

    /** Going OFFLINE publishes an event; the agent reacts after commit, asynchronously. */
    @Transactional
    public Provider changeProviderStatus(String providerId, ProviderStatus status) {
        Provider provider = providers.require(providerId);
        if (provider.changeStatus(status)) {
            activity.info(provider.getName() + " went OFFLINE - agent triggered");
            var invalidated = invalidatePending(s -> s.getProviderId().equals(providerId),
                    a -> a.getProviderId().equals(providerId), TriggerReason.PROVIDER_OFFLINE);
            events.publishEvent(new AgentEvents.ProviderWentOffline(providerId, invalidated));
        }
        return provider;
    }

    /** Cancelling publishes an event; the agent reacts after commit, asynchronously. */
    @Transactional
    public Slot changeSlotStatus(String slotId, SlotStatus status) {
        Slot slot = slots.require(slotId);
        if (slot.changeStatus(status)) {
            activity.info("Slot " + slotId + " CANCELLED - agent triggered");
            var invalidated = invalidatePending(s -> s.getId().equals(slotId),
                    a -> a.getSlotId().equals(slotId), TriggerReason.SLOT_CANCELLED);
            events.publishEvent(new AgentEvents.SlotWasCancelled(slotId, invalidated));
        }
        return slot;
    }

    /**
     * Rejects pending suggestions whose slot a disruption just made unbookable, so no patient is left waiting
     * on a dead option. The re-proposal keeps the suggestion's trigger unless the disruption also hit the
     * patient's own booking: the prompt must describe the patient's real situation.
     */
    private List<PlanRequest> invalidatePending(Predicate<Slot> hitSlot, Predicate<Appointment> hitOwnBooking,
                                                TriggerReason disruption) {
        List<RescheduleSuggestion> allPending = suggestions.findByStatus(SuggestionStatus.PENDING);
        Map<String, Slot> targets = slots.findAllById(allPending.stream().map(RescheduleSuggestion::getRecommendedSlotId).toList())
                .stream().collect(Collectors.toMap(Slot::getId, Function.identity()));
        List<PlanRequest> invalidated = new ArrayList<>();
        for (RescheduleSuggestion pending : allPending) {
            Slot target = targets.get(pending.getRecommendedSlotId());
            if (target == null || !hitSlot.test(target)) continue; // a dangling slot id is no reason to roll back
            pending.reject();
            Appointment appt = appointments.require(pending.getAppointmentId());
            TriggerReason trigger = hitOwnBooking.test(appt) ? disruption : pending.getTriggerReason();
            invalidated.add(new PlanRequest(appt.getId(), trigger));
            activity.info("Suggestion for " + appt.getPatientName() + " superseded (" + disruption + ") - re-proposing");
        }
        return invalidated;
    }

    @Transactional
    public Slot createSlot(String providerId, LocalDateTime start, int durationMinutes) {
        providers.require(providerId);
        return slots.save(new Slot(Ids.next("SLT"), providerId, start, durationMinutes, SlotStatus.OPEN));
    }

    /**
     * Accept = one transaction that updates the suggestion, the appointment, the old slot and the new slot.
     * With one pooled connection, SQLite runs accepts one after the other, so the loser of a race is stopped by
     * the state checks: {@link Slot#book()} refuses a non-OPEN slot and a superseded suggestion cannot be accepted.
     * {@code @Version} is the backstop if the pool ever grows: a concurrent write to the slot fails this commit, and
     * the provider is read with a forced version increment, so an accept and a concurrent provider status change
     * conflict and one of them fails (-> HTTP 409).
     */
    @Transactional
    public RescheduleSuggestion accept(String suggestionId) {
        RescheduleSuggestion suggestion = suggestions.require(suggestionId);
        Appointment appt = appointments.require(suggestion.getAppointmentId());
        Slot newSlot = slots.require(suggestion.getRecommendedSlotId());
        Provider newProvider = providers.requireForBooking(newSlot.getProviderId());
        if (!newProvider.isBookable()) {
            throw new DomainException(newProvider.getName() + " went offline after this suggestion was made");
        }

        suggestion.accept();
        newSlot.book();
        slots.findById(appt.getSlotId()).ifPresent(Slot::release);
        appt.rescheduleTo(newSlot.getProviderId(), newSlot.getId());
        slots.flush(); // surface version conflicts inside this transaction

        List<PlanRequest> superseded = new ArrayList<>();
        for (RescheduleSuggestion other : suggestions.findByStatus(SuggestionStatus.PENDING)) {
            if (other.getId().equals(suggestion.getId())) continue;
            if (other.getAppointmentId().equals(appt.getId())) {
                other.reject(); // alternatives for this appointment are moot now
            } else if (other.getRecommendedSlotId().equals(newSlot.getId())) {
                other.reject(); // slot is gone; the agent will find that patient something else
                superseded.add(new PlanRequest(other.getAppointmentId(), other.getTriggerReason()));
            }
        }
        if (!superseded.isEmpty()) {
            events.publishEvent(new AgentEvents.SuggestionsSuperseded(superseded));
        }
        activity.success("Accepted: " + appt.getPatientName() + " moved to " + newProvider.getName() + " (" + newSlot.getId() + ")");
        return suggestion;
    }

    /** Reject leaves the appointment RESCHEDULE_PENDING so staff can ask for another option. */
    @Transactional
    public RescheduleSuggestion reject(String suggestionId) {
        RescheduleSuggestion suggestion = suggestions.require(suggestionId);
        suggestion.reject();
        activity.info("Rejected suggestion " + suggestionId + " for appointment " + suggestion.getAppointmentId());
        return suggestion;
    }

    @Transactional
    public Appointment changeAppointmentStatus(String appointmentId, AppointmentStatus status) {
        Appointment appt = appointments.require(appointmentId);
        appt.changeStatus(status);
        return appt;
    }

}
