package com.slotmind.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentTest {

    private final Appointment appt = new Appointment("APT-001", "John Martinez", AppointmentType.ROUTINE, "PRV-001", "SLT-001");

    @Test
    void onlyCompletedCanBeSetDirectly() {
        assertThatThrownBy(() -> appt.changeStatus(AppointmentStatus.RESCHEDULE_PENDING))
                .isInstanceOf(DomainException.class)
                .hasMessage("Only COMPLETED can be set directly; use suggestions to reschedule");
        assertThat(appt.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void confirmedAppointmentCanBeCompletedDirectly() {
        appt.changeStatus(AppointmentStatus.COMPLETED);

        assertThat(appt.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
    }
}
