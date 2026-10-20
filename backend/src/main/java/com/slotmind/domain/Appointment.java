package com.slotmind.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "appointments")
public class Appointment {
    @Id
    private String id;
    @Column(name = "patient_name")
    private String patientName;
    @Enumerated(EnumType.STRING)
    @Column(name = "appointment_type")
    private AppointmentType appointmentType;
    @Column(name = "provider_id")
    private String providerId;
    @Column(name = "slot_id")
    private String slotId;
    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Version
    private Long version;

    protected Appointment() {}

    public Appointment(String id, String patientName, AppointmentType type, String providerId, String slotId) {
        this.id = id;
        this.patientName = patientName;
        this.appointmentType = type;
        this.providerId = providerId;
        this.slotId = slotId;
        this.status = AppointmentStatus.CONFIRMED;
        this.createdAt = LocalDateTime.now();
    }

    private void transition(AppointmentStatus target) {
        if (!status.canMoveTo(target)) {
            throw new DomainException("Appointment " + id + " cannot move " + status + " -> " + target);
        }
        status = target;
    }

    public void markReschedulePending() {
        if (status != AppointmentStatus.RESCHEDULE_PENDING) {
            transition(AppointmentStatus.RESCHEDULE_PENDING);
        }
    }

    public void rescheduleTo(String newProviderId, String newSlotId) {
        transition(AppointmentStatus.RESCHEDULED);
        this.providerId = newProviderId;
        this.slotId = newSlotId;
    }

    public void complete() {
        transition(AppointmentStatus.COMPLETED);
    }

    public String getId() { return id; }
    public String getPatientName() { return patientName; }
    public AppointmentType getAppointmentType() { return appointmentType; }
    public String getProviderId() { return providerId; }
    public String getSlotId() { return slotId; }
    public AppointmentStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
