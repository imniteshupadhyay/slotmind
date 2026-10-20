package com.slotmind.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * CONFIRMED -> RESCHEDULE_PENDING -> RESCHEDULED -> COMPLETED.
 * A RESCHEDULED appointment still holds a booked slot, so it can be disrupted again.
 */
public enum AppointmentStatus {
    CONFIRMED, RESCHEDULE_PENDING, RESCHEDULED, COMPLETED;

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
