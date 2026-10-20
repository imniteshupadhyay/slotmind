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
    /**
     * Optimistic lock: an accept checks this at commit, so a provider going offline mid-accept fails the accept.
     * The default lets ddl-auto add the column to an existing database without leaving NULL versions behind.
     */
    @Version
    @Column(columnDefinition = "bigint default 0")
    private Long version;

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

    /** Re-scheduling never crosses specialty: the one place that rule is checked. */
    public boolean sameSpecialtyAs(Provider other) {
        return other != null && specialty == other.specialty;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Specialty getSpecialty() { return specialty; }
    public ProviderStatus getStatus() { return status; }
}
