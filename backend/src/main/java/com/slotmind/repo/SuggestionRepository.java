package com.slotmind.repo;

import com.slotmind.domain.RescheduleSuggestion;
import com.slotmind.domain.SuggestionStatus;
import com.slotmind.domain.TriggerReason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuggestionRepository extends JpaRepository<RescheduleSuggestion, String> {
    boolean existsByAppointmentIdAndTriggerReasonAndStatus(String appointmentId, TriggerReason reason, SuggestionStatus status);

    List<RescheduleSuggestion> findByAppointmentIdAndStatus(String appointmentId, SuggestionStatus status);

    List<RescheduleSuggestion> findByStatus(SuggestionStatus status);

    List<RescheduleSuggestion> findAllByOrderByCreatedAtDesc();
}
