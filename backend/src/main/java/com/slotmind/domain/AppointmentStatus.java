package com.slotmind.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * CONFIRMED -> RESCHEDULE_PENDING -> RESCHEDULED -> COMPLETED.
 * A RESCHEDULED appointment still holds a booked slot, so it can be disrupted again.
 */
public enum AppointmentStatus {
    CONFIRMED, RESCHEDULE_PENDING, RESCHEDULED, COMPLETED;

    /**
     * Every status in which the appointment still occupies its slot: it is shown on that slot, and a disruption
     * of the slot or its provider re-plans it. Only a COMPLETED appointment is history.
     */
    public static final Set<AppointmentStatus> OCCUPYING_SLOT = EnumSet.of(CONFIRMED, RESCHEDULE_PENDING, RESCHEDULED);

    public boolean occupiesSlot() {
        return OCCUPYING_SLOT.contains(this);
    }

    public Set<AppointmentStatus> next() {
        return switch (this) {
            case CONFIRMED -> EnumSet.of(RESCHEDULE_PENDING, COMPLETED);
            case RESCHEDULE_PENDING -> EnumSet.of(RESCHEDULED);
            case RESCHEDULED -> EnumSet.of(RESCHEDULE_PENDING, COMPLETED);
            case COMPLETED -> EnumSet.noneOf(AppointmentStatus.class);
        };
    }

    public boolean canMoveTo(AppointmentStatus target) {
        return next().contains(target);
    }
}
