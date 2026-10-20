package com.slotmind.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotTest {

    private Slot slot(SlotStatus status) {
        return new Slot("SLT-001", "PRV-001", LocalDateTime.of(2026, 10, 20, 10, 0), 30, status);
    }

    @Test
    void bookedSlotCannotBeReopenedDirectly() {
        Slot booked = slot(SlotStatus.BOOKED);

        assertThatThrownBy(() -> booked.changeStatus(SlotStatus.OPEN))
                .isInstanceOf(DomainException.class)
                .hasMessage("Slot SLT-001 is booked; reschedule the appointment instead");
        assertThat(booked.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    void bookedSlotCanBeCancelled() {
        Slot booked = slot(SlotStatus.BOOKED);

        assertThat(booked.changeStatus(SlotStatus.CANCELLED)).isTrue();
        assertThat(booked.getStatus()).isEqualTo(SlotStatus.CANCELLED);
    }
}
