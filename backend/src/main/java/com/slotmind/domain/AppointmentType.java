package com.slotmind.domain;

public enum AppointmentType {
    URGENT(0), FOLLOW_UP(1), ROUTINE(2);

    private final int priority;

    AppointmentType(int priority) {
        this.priority = priority;
    }

    /** Lower is more pressing: urgent patients get first pick of scarce slots when a batch is re-scheduled. */
    public int priority() {
        return priority;
    }
}
