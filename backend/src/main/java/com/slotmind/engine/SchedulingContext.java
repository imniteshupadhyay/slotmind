package com.slotmind.engine;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.Provider;
import com.slotmind.domain.Slot;
import com.slotmind.domain.TriggerReason;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything a strategy needs to decide, as a read-only snapshot. Built outside any transaction so
 * slow strategies (LLM calls) never hold a database connection.
 *
 * @param appointment      the appointment that needs a new slot
 * @param originalProvider provider the appointment is currently booked with
 * @param originalSlot     slot the appointment currently holds (may be cancelled)
 * @param trigger          why we are re-scheduling
 * @param candidateSlots   OPEN, future slots of bookable providers, nearest first
 * @param providers        roster by id (includes offline providers, for context)
 * @param now              reference time for "nearest"
 */
public record SchedulingContext(
        Appointment appointment,
        Provider originalProvider,
        Slot originalSlot,
        TriggerReason trigger,
        List<Slot> candidateSlots,
        Map<String, Provider> providers,
        LocalDateTime now) {

    /** At most this many candidate slots go into a context (nearest first). */
    public static final int MAX_CANDIDATES = 25;
    /** The AI prompt lists only the first this-many candidates, to keep it short; the rule strategy sees them all. */
    public static final int MAX_PROMPT_CANDIDATES = 20;

    public Provider providerOf(Slot slot) {
        return providers.get(slot.getProviderId());
    }

    public Slot candidate(String slotId) {
        return candidateSlots.stream().filter(s -> s.getId().equals(slotId)).findFirst().orElse(null);
    }
}
