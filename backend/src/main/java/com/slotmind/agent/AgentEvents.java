package com.slotmind.agent;

import com.slotmind.domain.TriggerReason;

import java.util.List;

/** Domain events that wake the re-scheduling agent. Published inside the state-change transaction. */
public final class AgentEvents {
    private AgentEvents() {}

    /** A provider went OFFLINE: every active booking with them is affected. */
    public record ProviderWentOffline(String providerId) {}

    /** A slot was CANCELLED: the (at most one) booking on it is affected. */
    public record SlotWasCancelled(String slotId) {}

    /** Pending suggestions became stale because their slot was taken; re-propose for these appointments. */
    public record SuggestionsSuperseded(List<Superseded> items) {}

    public record Superseded(String appointmentId, TriggerReason trigger) {}
}
