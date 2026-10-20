package com.slotmind.repo;

import com.slotmind.domain.NotFoundException;
import com.slotmind.domain.Provider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, String> {

    /**
     * Loads a provider whose version is incremented at commit, even though the caller never changes it. A provider
     * status change committing concurrently then conflicts with this transaction, so one of the two fails.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select p from Provider p where p.id = :id")
    Optional<Provider> findForBookingById(String id);

    /** The provider, or {@link NotFoundException} (-> HTTP 404). */
    default Provider require(String id) {
        return findById(id).orElseThrow(() -> new NotFoundException("Provider", id));
    }

    /** {@link #findForBookingById}, or {@link NotFoundException} (-> HTTP 404). */
    default Provider requireForBooking(String id) {
        return findForBookingById(id).orElseThrow(() -> new NotFoundException("Provider", id));
    }
}
