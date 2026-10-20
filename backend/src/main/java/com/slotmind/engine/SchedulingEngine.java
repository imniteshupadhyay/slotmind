package com.slotmind.engine;

import com.slotmind.domain.Provider;
import com.slotmind.domain.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Strategy registry + the single entry point used by both HTTP and the async agent.
 * <ul>
 *   <li>Every {@link SchedulingStrategy} bean is registered by name; switch with {@link #setActive}.</li>
 *   <li>Every recommendation is validated against the context (slot really a candidate, provider consistent).</li>
 *   <li>If the active strategy fails or returns nothing valid, the rule-based strategy is used instead.</li>
 * </ul>
 */
@Service
public class SchedulingEngine {

    private static final Logger log = LoggerFactory.getLogger(SchedulingEngine.class);

    /** Result of a run: the chosen recommendation and a label of which strategy produced it. */
    public record Outcome(Recommendation recommendation, String strategyLabel) {}

    private final Map<String, SchedulingStrategy> strategies;
    private final AtomicReference<String> active;

    public SchedulingEngine(List<SchedulingStrategy> beans, @Value("${slotmind.strategy.active:ai}") String initial) {
        this.strategies = beans.stream().collect(Collectors.toMap(SchedulingStrategy::name, s -> s, (a, b) -> a, TreeMap::new));
        if (!strategies.containsKey(RuleBasedStrategy.NAME)) {
            throw new IllegalStateException("Rule-based strategy must be registered as the fallback");
        }
        this.active = new AtomicReference<>(strategies.containsKey(initial) ? initial : RuleBasedStrategy.NAME);
        log.info("Scheduling strategies registered: {} (active: {})", strategies.keySet(), active.get());
    }

    public String getActive() {
        return active.get();
    }

    public Set<String> available() {
        return strategies.keySet();
    }

    public void setActive(String name) {
        if (!strategies.containsKey(name)) {
            throw new IllegalArgumentException("Unknown strategy '" + name + "'. Available: " + strategies.keySet());
        }
        active.set(name);
        log.info("Active scheduling strategy switched to {}", name);
    }

    public Optional<Outcome> recommend(SchedulingContext ctx) {
        return recommend(ctx, text -> {});
    }

    public Optional<Outcome> recommend(SchedulingContext ctx, Consumer<String> progress) {
        String name = active.get();
        SchedulingStrategy strategy = strategies.get(name);
        String fallbackReason;
        try {
            Optional<Recommendation> first = firstValid(strategy.recommend(ctx, progress), ctx);
            if (first.isPresent()) {
                return Optional.of(new Outcome(first.get(), name));
            }
            fallbackReason = name + " returned no valid option";
        } catch (Exception e) {
            fallbackReason = name + " failed: " + e.getMessage();
            log.warn("Strategy {} failed for appointment {}: {}", name, ctx.appointment().getId(), e.toString());
        }
        if (name.equals(RuleBasedStrategy.NAME)) {
            return Optional.empty();
        }
        progress.accept("\n[fallback] " + fallbackReason + " - using rule-based strategy\n");
        String label = RuleBasedStrategy.NAME + " (fallback: " + truncate(fallbackReason) + ")";
        return firstValid(strategies.get(RuleBasedStrategy.NAME).recommend(ctx), ctx).map(r -> new Outcome(r, label));
    }

    /** Defence in depth: whatever a strategy says, only accept slots that are real, OPEN candidates. */
    private Optional<Recommendation> firstValid(List<Recommendation> recs, SchedulingContext ctx) {
        if (recs == null) return Optional.empty();
        for (Recommendation r : recs) {
            Slot slot = r == null ? null : ctx.candidate(r.slotId());
            if (slot == null) {
                log.warn("Discarding recommendation for unknown/unavailable slot {}", r == null ? null : r.slotId());
                continue;
            }
            Provider slotProvider = ctx.providerOf(slot);
            if (slotProvider == null || !slotProvider.isBookable()) continue;
            if (slotProvider.getSpecialty() != ctx.originalProvider().getSpecialty() && sameSpecialtyAvailable(ctx)) {
                log.warn("Discarding recommendation {}: specialty {} while a {} slot is open",
                        slot.getId(), slotProvider.getSpecialty(), ctx.originalProvider().getSpecialty());
                continue;
            }
            // the alternate provider is, by definition, whoever owns the recommended slot
            String alternate = slot.getProviderId().equals(ctx.originalProvider().getId()) ? null : slot.getProviderId();
            double confidence = Math.max(0, Math.min(1, r.confidence()));
            String reasoning = r.reasoning() == null || r.reasoning().isBlank()
                    ? "We found a new appointment time for you " + Phrases.when(slot.getStartTime(), ctx.now()) + "."
                    : r.reasoning().trim();
            return Optional.of(new Recommendation(slot.getId(), alternate, confidence, reasoning));
        }
        return Optional.empty();
    }

    private static boolean sameSpecialtyAvailable(SchedulingContext ctx) {
        return ctx.candidateSlots().stream()
                .map(ctx::providerOf)
                .anyMatch(p -> p != null && p.getSpecialty() == ctx.originalProvider().getSpecialty());
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
