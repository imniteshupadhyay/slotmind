package com.slotmind.service;

import com.slotmind.agent.AgentActivityLog;
import com.slotmind.domain.*;
import com.slotmind.engine.Phrases;
import com.slotmind.engine.Recommendation;
import com.slotmind.engine.SchedulingContext;
import com.slotmind.engine.SchedulingEngine;
import com.slotmind.repo.AppointmentRepository;
import com.slotmind.repo.ProviderRepository;
import com.slotmind.repo.SlotRepository;
import com.slotmind.repo.SuggestionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Produces a {@link RescheduleSuggestion} for one appointment. Shared by the HTTP "suggest" endpoints and
 * the async agent, so both go through the same engine contract. Owns the rule that an appointment has at most
 * one PENDING suggestion.
 *
 * <p>Three phases so the (possibly slow) LLM call never holds the single SQLite connection:
 * read snapshot (short tx) -> run engine (no tx) -> write suggestion (short tx, re-checking dedupe).
 */
@Service
public class ReschedulingService {

    private final AppointmentRepository appointments;
    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final SuggestionRepository suggestions;
    private final SchedulingEngine engine;
    private final TransactionTemplate tx;
    private final AgentActivityLog activity;

    public ReschedulingService(AppointmentRepository appointments, ProviderRepository providers, SlotRepository slots,
                               SuggestionRepository suggestions, SchedulingEngine engine, TransactionTemplate tx,
                               AgentActivityLog activity) {
        this.appointments = appointments;
        this.providers = providers;
        this.slots = slots;
        this.suggestions = suggestions;
        this.engine = engine;
        this.tx = tx;
        this.activity = activity;
    }

    /** Result of a proposal attempt; {@code created=false} means an existing pending suggestion was reused. */
    public record Proposal(RescheduleSuggestion suggestion, boolean created) {}

    /** Which existing pending suggestion a proposal reuses instead of planning a new one. */
    private enum Reuse {
        /** Agent: the same trigger is a duplicate; a new trigger supersedes the older suggestion. */
        SAME_TRIGGER,
        /** Manual Suggest: any pending suggestion answers the staff click. */
        ANY_TRIGGER;

        Optional<RescheduleSuggestion> pick(List<RescheduleSuggestion> pending, TriggerReason trigger) {
            return pending.stream().filter(s -> this == ANY_TRIGGER || s.getTriggerReason() == trigger).findFirst();
        }
    }

    /** True while the appointment is waiting on a PENDING suggestion. */
    public boolean hasPending(String appointmentId) {
        return !pending(appointmentId).isEmpty();
    }

    public Optional<Proposal> propose(String appointmentId, TriggerReason trigger) {
        return propose(appointmentId, trigger, text -> {});
    }

    /**
     * Agent path: reuses a pending suggestion with the same trigger. Otherwise the new suggestion supersedes
     * (rejects) the appointment's other pending ones, which describe an older situation.
     */
    public Optional<Proposal> propose(String appointmentId, TriggerReason trigger, Consumer<String> progress) {
        return propose(appointmentId, trigger, progress, Reuse.SAME_TRIGGER);
    }

    /** Manual Suggest (staff click): reuses ANY pending suggestion for the appointment, whatever its trigger. */
    public Optional<Proposal> suggestManually(String appointmentId) {
        return suggestManually(appointmentId, text -> {});
    }

    public Optional<Proposal> suggestManually(String appointmentId, Consumer<String> progress) {
        return propose(appointmentId, TriggerReason.INITIAL, progress, Reuse.ANY_TRIGGER);
    }

    private Optional<Proposal> propose(String appointmentId, TriggerReason trigger, Consumer<String> progress, Reuse reuse) {
        Optional<RescheduleSuggestion> existing = reuse.pick(pending(appointmentId), trigger);
        if (existing.isPresent()) {
            activity.info("Skipped " + appointmentId + ": a pending " + existing.get().getTriggerReason()
                    + " suggestion already exists");
            return existing.map(s -> new Proposal(s, false));
        }

        SchedulingContext ctx = tx.execute(status -> buildContext(appointmentId, trigger));
        Optional<SchedulingEngine.Outcome> outcome = engine.recommend(ctx, progress);
        if (outcome.isEmpty()) {
            activity.warn("No " + ctx.originalProvider().getSpecialty() + " slot available for " + ctx.appointment().getPatientName()
                    + " (" + appointmentId + "), needs a call from staff");
            return Optional.empty();
        }

        Proposal proposal = tx.execute(status -> save(appointmentId, trigger, reuse, outcome.get()));
        if (proposal.created()) {
            Slot slot = ctx.candidate(proposal.suggestion().getRecommendedSlotId());
            activity.success("Suggested " + ctx.providerOf(slot).getName() + " " + Phrases.when(slot.getStartTime(), ctx.now())
                    + " for " + ctx.appointment().getPatientName() + " [" + trigger + ", via " + outcome.get().strategyLabel() + "]");
        }
        return Optional.of(proposal);
    }

    private List<RescheduleSuggestion> pending(String appointmentId) {
        return suggestions.findByAppointmentIdAndStatus(appointmentId, SuggestionStatus.PENDING);
    }

    private SchedulingContext buildContext(String appointmentId, TriggerReason trigger) {
        Appointment appt = appointments.require(appointmentId);
        if (!appt.getStatus().occupiesSlot()) {
            throw new DomainException("Appointment " + appointmentId + " is already completed");
        }
        Map<String, Provider> roster = providers.findAll().stream()
                .collect(Collectors.toMap(Provider::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Provider original = roster.get(appt.getProviderId());
        Slot originalSlot = slots.findById(appt.getSlotId()).orElse(null);
        LocalDateTime now = LocalDateTime.now();

        // Holds: slots already offered to OTHER appointments, so a batch (provider offline)
        // doesn't propose the same slot to everyone. Also skip slots this patient already rejected.
        Set<String> heldByOthers = new HashSet<>();
        Set<String> rejectedByPatient = new HashSet<>();
        for (RescheduleSuggestion s : suggestions.findAll()) {
            if (s.getStatus() == SuggestionStatus.PENDING && !s.getAppointmentId().equals(appointmentId)) {
                heldByOthers.add(s.getRecommendedSlotId());
            }
            if (s.getStatus() == SuggestionStatus.REJECTED && s.getAppointmentId().equals(appointmentId)) {
                rejectedByPatient.add(s.getRecommendedSlotId());
            }
        }

        List<Slot> open = slots.findByStatusAndStartTimeAfterOrderByStartTimeAsc(SlotStatus.OPEN, now).stream()
                .filter(s -> !s.getId().equals(appt.getSlotId()))
                .filter(s -> roster.containsKey(s.getProviderId()) && roster.get(s.getProviderId()).isBookable())
                .filter(s -> !rejectedByPatient.contains(s.getId()))
                .filter(s -> roster.get(s.getProviderId()).sameSpecialtyAs(original))
                .toList();
        // Relax the Holds only when no unheld slot of the right specialty is left: sharing beats giving up.
        List<Slot> unheld = open.stream().filter(s -> !heldByOthers.contains(s.getId())).toList();
        List<Slot> candidates = (unheld.isEmpty() ? open : unheld).stream().limit(SchedulingContext.MAX_CANDIDATES).toList();

        return new SchedulingContext(appt, original, originalSlot, trigger, candidates, roster, now);
    }

    /** Re-checks dedupe inside the write transaction, then keeps the appointment at one PENDING suggestion. */
    private Proposal save(String appointmentId, TriggerReason trigger, Reuse reuse, SchedulingEngine.Outcome outcome) {
        List<RescheduleSuggestion> pending = pending(appointmentId);
        Optional<RescheduleSuggestion> raced = reuse.pick(pending, trigger);
        if (raced.isPresent()) return new Proposal(raced.get(), false);
        pending.forEach(RescheduleSuggestion::reject); // superseded by the new one

        Appointment appt = appointments.require(appointmentId);
        appt.markReschedulePending();
        Recommendation r = outcome.recommendation();
        RescheduleSuggestion s = new RescheduleSuggestion(Ids.next("SUG"), appointmentId, r.slotId(), r.alternateProviderId(),
                r.confidence(), r.reasoning(), trigger, outcome.strategyLabel());
        return new Proposal(suggestions.save(s), true);
    }
}
