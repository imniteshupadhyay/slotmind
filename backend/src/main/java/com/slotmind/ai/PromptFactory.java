package com.slotmind.ai;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.Provider;
import com.slotmind.domain.Slot;
import com.slotmind.engine.Phrases;
import com.slotmind.engine.SchedulingContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the prompts. Provider-offline and slot-cancelled are different recovery situations, so each
 * trigger gets its own situation brief and preference rules on top of a shared output contract.
 */
@Component
public class PromptFactory {

    static final int MAX_CANDIDATES = 20;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String SYSTEM = """
            You are SlotMind, a scheduling assistant for the CareBridge clinic front desk.
            You pick ONE replacement slot for a patient whose appointment was disrupted.

            Hard rules:
            - Choose slotId ONLY from the "Open slots" list. Never invent IDs.
            - Every listed slot already matches the original provider's specialty; never suggest any
              other specialty. A PEDIATRIC patient must stay with a pediatrician.
            - Provider status meanings: AVAILABLE and BUSY providers can both be booked (BUSY just means
              they are seeing patients right now). OFFLINE providers cannot. Never tell the patient a
              doctor is "busy".
            - If the chosen slot's provider differs from the patient's original provider, set
              alternateProviderId to that slot's provider ID; otherwise set it to null.
            - confidence (0-1) must be calibrated: ~0.9 same provider and close in time; ~0.75-0.85
              same specialty, different provider; lower the further the slot is from the original time.
            - reasoning: 1-3 short, warm sentences addressed to the patient as "you". Staff will read it
              aloud over the phone. Mention the doctor's name and the day/time in plain words.
              Be truthful about timing: each slot says whether it is earlier or later than the original
              time; never call a later slot "earlier".
              No IDs, no internal jargon, no mention of "slots", "strategies" or "the system".

            Respond with ONLY a JSON object, no markdown, in exactly this shape:
            {"slotId":"SLT-...","alternateProviderId":null,"confidence":0.85,"reasoning":"..."}
            """;

    public List<LlmGateway.Message> build(SchedulingContext ctx) {
        return List.of(new LlmGateway.Message("system", SYSTEM), new LlmGateway.Message("user", userPrompt(ctx)));
    }

    private String userPrompt(SchedulingContext ctx) {
        Appointment a = ctx.appointment();
        Provider original = ctx.originalProvider();
        String originalTime = ctx.originalSlot() == null ? "unknown"
                : ctx.originalSlot().getStartTime().format(TS) + " (" + Phrases.when(ctx.originalSlot().getStartTime(), ctx.now()) + ")";

        return """
                ## Situation
                %s

                ## Patient appointment
                - Patient: %s
                - Appointment type: %s (%s)
                - Original provider: %s, %s, currently %s
                - Original time: %s
                - Current time: %s

                ## Preferences for this situation
                %s

                ## Provider roster
                %s

                ## Open slots (nearest first)
                %s
                """.formatted(
                situation(ctx), a.getPatientName(), a.getAppointmentType(), urgencyNote(a), original.getName(),
                original.getSpecialty(), original.getStatus(), originalTime, ctx.now().format(TS),
                preferences(ctx), roster(ctx), slots(ctx));
    }

    private static String situation(SchedulingContext ctx) {
        String doctor = ctx.originalProvider().getName();
        return switch (ctx.trigger()) {
            case PROVIDER_OFFLINE -> doctor + " has gone OFFLINE (called in sick) and cannot see any patients. "
                    + "Every appointment with them must move to ANOTHER provider.";
            case SLOT_CANCELLED -> "The patient's appointment slot was CANCELLED (e.g. the room became unavailable). "
                    + doctor + " is still working, so the patient can usually stay with them at a different time.";
            case INITIAL -> "The front desk asked for a new time for this appointment.";
        };
    }

    private static String preferences(SchedulingContext ctx) {
        return switch (ctx.trigger()) {
            case PROVIDER_OFFLINE -> """
                    - The original provider is unavailable: pick a slot with a DIFFERENT provider.
                    - URGENT: earliest suitable slot wins. ROUTINE/FOLLOW_UP: earliest same-specialty slot.""";
            case SLOT_CANCELLED -> """
                    - Strongly prefer the SAME provider's nearest open slot (continuity of care).
                    - Only switch provider if the same provider has no open slot, or the appointment is
                      URGENT and another same-specialty provider is available much sooner.""";
            case INITIAL -> """
                    - Prefer the same provider's nearest open slot; otherwise the nearest same-specialty slot.""";
        };
    }

    private static String urgencyNote(Appointment a) {
        return switch (a.getAppointmentType()) {
            case URGENT -> "should be seen as soon as possible";
            case FOLLOW_UP -> "continuity with the same doctor is valuable";
            case ROUTINE -> "flexible on timing";
        };
    }

    private static String roster(SchedulingContext ctx) {
        return ctx.providers().values().stream()
                .map(p -> "- " + p.getId() + ": " + p.getName() + " | " + p.getSpecialty() + " | " + p.getStatus())
                .collect(Collectors.joining("\n"));
    }

    private static String slots(SchedulingContext ctx) {
        if (ctx.candidateSlots().isEmpty()) return "(none)";
        return ctx.candidateSlots().stream().limit(MAX_CANDIDATES).map(s -> slotLine(s, ctx)).collect(Collectors.joining("\n"));
    }

    private static String slotLine(Slot s, SchedulingContext ctx) {
        Provider p = ctx.providerOf(s);
        long mins = Duration.between(ctx.now(), s.getStartTime()).toMinutes();
        boolean sameProvider = p.getId().equals(ctx.originalProvider().getId());
        return "- slotId=" + s.getId() + " | providerId=" + p.getId() + " (" + p.getName() + ", " + p.getSpecialty() + ")"
                + " | same provider: " + (sameProvider ? "yes" : "no")
                + " | starts " + s.getStartTime().format(TS) + " (in " + mins + " min, " + Phrases.when(s.getStartTime(), ctx.now()) + ")"
                + " | " + relativeToOriginal(s, ctx)
                + " | " + s.getDurationMinutes() + " min";
    }

    private static String relativeToOriginal(Slot s, SchedulingContext ctx) {
        if (ctx.originalSlot() == null) return "original time unknown";
        long diff = Duration.between(ctx.originalSlot().getStartTime(), s.getStartTime()).toMinutes();
        if (diff == 0) return "same time as original";
        String amount = Math.abs(diff) >= 120 ? (Math.abs(diff) / 60) + " h" : Math.abs(diff) + " min";
        return amount + (diff > 0 ? " LATER than original" : " EARLIER than original");
    }
}
