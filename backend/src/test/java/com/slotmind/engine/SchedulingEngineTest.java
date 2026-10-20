package com.slotmind.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.ai.AiSchedulingStrategy;
import com.slotmind.ai.LlmException;
import com.slotmind.ai.PromptFactory;
import com.slotmind.domain.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulingEngineTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 20, 9, 0);
    private final Provider chen = new Provider("PRV-001", "Dr. Sarah Chen", Specialty.GENERAL, ProviderStatus.AVAILABLE);
    private final Provider verma = new Provider("PRV-004", "Dr. Rahul Verma", Specialty.GENERAL, ProviderStatus.AVAILABLE);
    private final Provider okonkwo = new Provider("PRV-002", "Dr. James Okonkwo", Specialty.SPECIALIST, ProviderStatus.AVAILABLE);
    private final Appointment appt = new Appointment("APT-001", "John Martinez", AppointmentType.ROUTINE, "PRV-001", "SLT-001");
    private final Slot original = new Slot("SLT-001", "PRV-001", now.plusHours(1), 30, SlotStatus.BOOKED);

    private SchedulingContext ctx(TriggerReason trigger, Slot... candidates) {
        return new SchedulingContext(appt, chen, original, trigger, List.of(candidates),
                Map.of(chen.getId(), chen, verma.getId(), verma, okonkwo.getId(), okonkwo), now);
    }

    @Test
    void ruleBasedPrefersSameProviderThenSameSpecialty() {
        Slot okonkwoSoon = new Slot("S-A", "PRV-002", now.plusHours(1), 30, SlotStatus.OPEN);
        Slot vermaSoon = new Slot("S-B", "PRV-004", now.plusHours(2), 30, SlotStatus.OPEN);
        Slot chenLater = new Slot("S-C", "PRV-001", now.plusHours(5), 30, SlotStatus.OPEN);

        List<Recommendation> recs = new RuleBasedStrategy().recommend(ctx(TriggerReason.SLOT_CANCELLED, okonkwoSoon, vermaSoon, chenLater));

        assertThat(recs).extracting(Recommendation::slotId).containsExactly("S-C", "S-B"); // specialist excluded
        assertThat(recs.get(0).alternateProviderId()).isNull();
        assertThat(recs.get(1).alternateProviderId()).isEqualTo("PRV-004");
        assertThat(recs.get(1).reasoning()).contains("Dr. Rahul Verma").doesNotContain("S-B");
    }

    @Test
    void aiOutputMustReferenceAnOpenCandidate() {
        AiSchedulingStrategy ai = new AiSchedulingStrategy(null, new PromptFactory(), new ObjectMapper());
        Slot verma = new Slot("S-B", "PRV-004", now.plusHours(2), 30, SlotStatus.OPEN);
        SchedulingContext c = ctx(TriggerReason.PROVIDER_OFFLINE, verma);

        assertThatThrownBy(() -> ai.parse("{\"slotId\":\"SLT-999\",\"confidence\":0.9,\"reasoning\":\"x\"}", c))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> ai.parse("sorry, I cannot help", c)).isInstanceOf(LlmException.class);

        Recommendation ok = ai.parse("<think>hmm</think>```json\n{\"slotId\":\"S-B\",\"alternateProviderId\":\"PRV-004\","
                + "\"confidence\":0.91,\"reasoning\":\"Dr. Verma can see you at 11.\"}\n```", c);
        assertThat(ok.slotId()).isEqualTo("S-B");
    }

    @Test
    void engineFallsBackToRulesWhenAiFails() {
        SchedulingStrategy brokenAi = new SchedulingStrategy() {
            public String name() { return "ai"; }
            public List<Recommendation> recommend(SchedulingContext c) { throw new LlmException("gateway down"); }
        };
        SchedulingEngine engine = new SchedulingEngine(List.of(brokenAi, new RuleBasedStrategy()), "ai");
        Slot verma = new Slot("S-B", "PRV-004", now.plusHours(2), 30, SlotStatus.OPEN);

        Optional<SchedulingEngine.Outcome> out = engine.recommend(ctx(TriggerReason.PROVIDER_OFFLINE, verma));

        assertThat(out).isPresent();
        assertThat(out.get().recommendation().slotId()).isEqualTo("S-B");
        assertThat(out.get().strategyLabel()).startsWith("rule (fallback: ai failed: gateway down");
    }

    @Test
    void engineRejectsWrongSpecialtyWhenAMatchingSlotIsOpen() {
        SchedulingStrategy careless = new SchedulingStrategy() {
            public String name() { return "ai"; }
            public List<Recommendation> recommend(SchedulingContext c) {
                return List.of(new Recommendation("S-SPEC", "PRV-002", 0.95, "specialist"));
            }
        };
        SchedulingEngine engine = new SchedulingEngine(List.of(careless, new RuleBasedStrategy()), "ai");
        Slot specialist = new Slot("S-SPEC", "PRV-002", now.plusHours(1), 30, SlotStatus.OPEN);
        Slot general = new Slot("S-GEN", "PRV-004", now.plusHours(3), 30, SlotStatus.OPEN);

        SchedulingEngine.Outcome out = engine.recommend(ctx(TriggerReason.PROVIDER_OFFLINE, specialist, general)).orElseThrow();

        assertThat(out.recommendation().slotId()).isEqualTo("S-GEN");
        assertThat(out.strategyLabel()).startsWith("rule (fallback");
    }

    @Test
    void engineDiscardsHallucinatedSlotsAndFixesAlternateProvider() {
        SchedulingStrategy sloppy = new SchedulingStrategy() {
            public String name() { return "ai"; }
            public List<Recommendation> recommend(SchedulingContext c) {
                return List.of(new Recommendation("NOPE", null, 0.99, "x"), new Recommendation("S-B", null, 1.7, "ok"));
            }
        };
        SchedulingEngine engine = new SchedulingEngine(List.of(sloppy, new RuleBasedStrategy()), "ai");
        Slot verma = new Slot("S-B", "PRV-004", now.plusHours(2), 30, SlotStatus.OPEN);

        Recommendation r = engine.recommend(ctx(TriggerReason.PROVIDER_OFFLINE, verma)).orElseThrow().recommendation();

        assertThat(r.slotId()).isEqualTo("S-B");
        assertThat(r.alternateProviderId()).isEqualTo("PRV-004");
        assertThat(r.confidence()).isEqualTo(1.0);
    }
}
