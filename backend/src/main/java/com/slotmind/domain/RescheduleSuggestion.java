package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reschedule_suggestions")
public class RescheduleSuggestion {
    @Id
    private String id;
    @Column(name = "appointment_id")
    private String appointmentId;
    @Column(name = "recommended_slot_id")
    private String recommendedSlotId;
    /** Set when the recommended slot belongs to a different provider than the original appointment. */
    @Column(name = "alternate_provider_id")
    private String alternateProviderId;
    private double confidence;
    @Column(length = 2000)
    private String reasoning;
    @Enumerated(EnumType.STRING)
    private SuggestionStatus status;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_reason")
    private TriggerReason triggerReason;
    /** Which strategy produced it, e.g. "ai" or "rule (fallback: ...)"; see {@code engine.StrategyLabel}. */
    @Column(length = 500)
    private String strategy;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;
    @Version
    private Long version;

    protected RescheduleSuggestion() {}

    public RescheduleSuggestion(String id, String appointmentId, String recommendedSlotId, String alternateProviderId,
                                double confidence, String reasoning, TriggerReason triggerReason, String strategy) {
        this.id = id;
        this.appointmentId = appointmentId;
        this.recommendedSlotId = recommendedSlotId;
        this.alternateProviderId = alternateProviderId;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.triggerReason = triggerReason;
        this.strategy = strategy;
        this.status = SuggestionStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    private void decide(SuggestionStatus target) {
        if (status != SuggestionStatus.PENDING) {
            throw new DomainException("Suggestion " + id + " is already " + status);
        }
        status = target;
        decidedAt = LocalDateTime.now();
    }

    public void accept() { decide(SuggestionStatus.ACCEPTED); }

    public void reject() { decide(SuggestionStatus.REJECTED); }

    public String getId() { return id; }
    public String getAppointmentId() { return appointmentId; }
    public String getRecommendedSlotId() { return recommendedSlotId; }
    public String getAlternateProviderId() { return alternateProviderId; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public SuggestionStatus getStatus() { return status; }
    public TriggerReason getTriggerReason() { return triggerReason; }
    public String getStrategy() { return strategy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
}
