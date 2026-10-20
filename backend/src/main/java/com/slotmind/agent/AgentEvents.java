package com.slotmind.agent;

import com.slotmind.domain.TriggerReason;

import java.util.List;

/** Domain events that wake the re-scheduling agent. Published inside the state-change transaction. */
public final class AgentEvents {
    private AgentEvents() {}

    /**
     * A provider went OFFLINE: every booking with them is affected, plus the patients whose pending
     * suggestion pointed at one of their slots ({@code invalidated}, already rejected).
     */
    public record ProviderWentOffline(String providerId, List<PlanRequest> invalidated) {}

    /** A slot was CANCELLED: the (at most one) booking on it, plus patients whose pending suggestion pointed at it. */
    public record SlotWasCancelled(String slotId, List<PlanRequest> invalidated) {}

    /** Pending suggestions became stale because their slot was taken; re-propose for these appointments. */
    public record SuggestionsSuperseded(List<PlanRequest> items) {}

    /** One unit of agent work: plan a new slot for this appointment, describing its situation with this trigger. */
    public record PlanRequest(String appointmentId, TriggerReason trigger) {}
}
