package com.slotmind.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "providers")
public class Provider {
    @Id
    private String id;
    private String name;
    @Enumerated(EnumType.STRING)
    private Specialty specialty;
    @Enumerated(EnumType.STRING)
    private ProviderStatus status;

    protected Provider() {}

    public Provider(String id, String name, Specialty specialty, ProviderStatus status) {
        this.id = id;
        this.name = name;
        this.specialty = specialty;
        this.status = status;
    }

    /** Returns true when this change takes the provider offline, i.e. re-scheduling must be triggered. */
    public boolean changeStatus(ProviderStatus target) {
        boolean goingOffline = target == ProviderStatus.OFFLINE && status != ProviderStatus.OFFLINE;
        this.status = target;
        return goingOffline;
    }

    public boolean isBookable() {
        return status != ProviderStatus.OFFLINE;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Specialty getSpecialty() { return specialty; }
    public ProviderStatus getStatus() { return status; }
}
