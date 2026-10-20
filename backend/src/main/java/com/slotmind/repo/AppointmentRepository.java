package com.slotmind.repo;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.AppointmentStatus;
import com.slotmind.domain.NotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, String> {
    List<Appointment> findAllByOrderByCreatedAtAsc();

    List<Appointment> findByStatusOrderByCreatedAtAsc(AppointmentStatus status);

    List<Appointment> findByProviderIdAndStatusIn(String providerId, Collection<AppointmentStatus> statuses);

    List<Appointment> findBySlotIdAndStatusIn(String slotId, Collection<AppointmentStatus> statuses);

    /** The appointment, or {@link NotFoundException} (-> HTTP 404). */
    default Appointment require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Appointment", id));
    }
}
