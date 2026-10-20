package com.slotmind.engine;

/**
 * The stored "which strategy produced this suggestion" label, e.g. {@code "ai"} or
 * {@code "rule (fallback: ai failed: ...)"}. The only place that writes or reads the fallback format.
 */
public final class StrategyLabel {

    private static final String FALLBACK_PREFIX = RuleBasedStrategy.NAME + " (fallback: ";
    private static final int MAX_REASON = 200;

    private StrategyLabel() {}

    /** Label for a suggestion the rule-based strategy made because the active strategy could not. */
    public static String fallback(String reason) {
        String r = reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) + "..." : reason;
        return FALLBACK_PREFIX + r + ")";
    }

    public static boolean isFallback(String label) {
        return label != null && label.startsWith(FALLBACK_PREFIX) && label.endsWith(")");
    }

    /** Why the active strategy was not used, or null when the label is not a fallback. */
    public static String fallbackReason(String label) {
        return isFallback(label) ? label.substring(FALLBACK_PREFIX.length(), label.length() - 1) : null;
    }
}
