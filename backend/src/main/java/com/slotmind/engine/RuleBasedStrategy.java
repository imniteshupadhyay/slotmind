package com.slotmind.engine;

import com.slotmind.domain.Provider;
import com.slotmind.domain.Slot;
import com.slotmind.domain.TriggerReason;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic baseline: the original provider's nearest open slot; if none (or the provider is
 * offline), the nearest open slot with a provider of the same specialty.
 */
@Component
public class RuleBasedStrategy implements SchedulingStrategy {

    public static final String NAME = "rule";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Recommendation> recommend(SchedulingContext ctx) {
        Provider original = ctx.originalProvider();
        Comparator<Slot> nearest = Comparator.comparing(Slot::getStartTime);

        List<Slot> sameProvider = ctx.candidateSlots().stream()
                .filter(s -> s.getProviderId().equals(original.getId()))
                .sorted(nearest)
                .toList();
        List<Slot> sameSpecialty = ctx.candidateSlots().stream()
                .filter(s -> !s.getProviderId().equals(original.getId()))
                .filter(s -> ctx.providerOf(s) != null && ctx.providerOf(s).getSpecialty() == original.getSpecialty())
                .sorted(nearest)
                .toList();

        List<Recommendation> out = new ArrayList<>();
        for (Slot s : sameProvider) {
            out.add(new Recommendation(s.getId(), null, confidence(0.92, s, ctx), sameProviderReason(s, ctx)));
        }
        for (Slot s : sameSpecialty) {
            Provider alt = ctx.providerOf(s);
            out.add(new Recommendation(s.getId(), alt.getId(), confidence(0.78, s, ctx), alternateReason(s, alt, ctx)));
        }
        return out;
    }

    /** Further-out slots are a little less likely to suit the patient. */
    private static double confidence(double base, Slot s, SchedulingContext ctx) {
        long hours = Math.max(0, Duration.between(ctx.now(), s.getStartTime()).toHours());
        double c = base - Math.min(0.25, hours * 0.01);
        return Math.round(c * 100) / 100.0;
    }

    private static String sameProviderReason(Slot s, SchedulingContext ctx) {
        String doctor = ctx.originalProvider().getName();
        String when = Phrases.when(s.getStartTime(), ctx.now());
        if (ctx.trigger() == TriggerReason.SLOT_CANCELLED) {
            return "Your original appointment time is no longer available, but " + doctor
                    + " can still see you " + when + ". You'll stay with the same doctor.";
        }
        return "We can move your appointment with " + doctor + " to " + when
                + ". This is the earliest time they have available.";
    }

    private static String alternateReason(Slot s, Provider alt, SchedulingContext ctx) {
        String when = Phrases.when(s.getStartTime(), ctx.now());
        String opening = ctx.trigger() == TriggerReason.PROVIDER_OFFLINE
                ? ctx.originalProvider().getName() + " is unavailable for your appointment, so "
                : "Your original time is no longer available and " + ctx.originalProvider().getName()
                  + " has no other openings soon, so ";
        return opening + "we've found you an opening with " + alt.getName() + ", who is also "
                + Phrases.specialty(alt.getSpecialty()) + ", " + when + ".";
    }
}
