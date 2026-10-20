package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "slots")
public class Slot {
    @Id
    private String id;
    @Column(name = "provider_id")
    private String providerId;
    @Column(name = "start_time")
    private LocalDateTime startTime;
    @Column(name = "duration_minutes")
    private int durationMinutes;
    @Enumerated(EnumType.STRING)
    private SlotStatus status;
    /** Optimistic lock: two concurrent writers to the same slot cannot both win. */
    @Version
    private Long version;

    protected Slot() {}

    public Slot(String id, String providerId, LocalDateTime startTime, int durationMinutes, SlotStatus status) {
        this.id = id;
        this.providerId = providerId;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
        this.status = status;
    }

    public void book() {
        if (status != SlotStatus.OPEN) {
            throw new DomainException("Slot " + id + " is " + status + " and cannot be booked");
        }
        status = SlotStatus.BOOKED;
    }

    /** Frees a booked slot when its appointment moves away. A cancelled slot stays cancelled. */
    public void release() {
        if (status == SlotStatus.BOOKED) {
            status = SlotStatus.OPEN;
        }
    }

    /** Returns true when this change cancels the slot, i.e. re-scheduling must be triggered. */
    public boolean changeStatus(SlotStatus target) {
        if (target == SlotStatus.BOOKED) {
            throw new DomainException("Slots are booked through appointments, not directly");
        }
        boolean cancelling = target == SlotStatus.CANCELLED && status != SlotStatus.CANCELLED;
        this.status = target;
        return cancelling;
    }

    public String getId() { return id; }
    public String getProviderId() { return providerId; }
    public LocalDateTime getStartTime() { return startTime; }
    public int getDurationMinutes() { return durationMinutes; }
    public SlotStatus getStatus() { return status; }
    public Long getVersion() { return version; }
}
