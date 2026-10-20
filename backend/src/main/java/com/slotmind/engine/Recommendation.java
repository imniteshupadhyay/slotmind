package com.slotmind.engine;

/**
 * One ranked option returned by a strategy.
 *
 * @param slotId              recommended OPEN slot
 * @param alternateProviderId provider of that slot when it differs from the original provider, else null
 * @param confidence          0..1
 * @param reasoning           patient-friendly explanation staff can read aloud
 */
public record Recommendation(String slotId, String alternateProviderId, double confidence, String reasoning) {
}
