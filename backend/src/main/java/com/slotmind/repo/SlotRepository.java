package com.slotmind.repo;

import com.slotmind.domain.Slot;
import com.slotmind.domain.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface SlotRepository extends JpaRepository<Slot, String> {
    List<Slot> findAllByOrderByStartTimeAsc();

    List<Slot> findByStatusAndStartTimeAfterOrderByStartTimeAsc(SlotStatus status, LocalDateTime after);
}
