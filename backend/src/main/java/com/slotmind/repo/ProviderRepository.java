package com.slotmind.repo;

import com.slotmind.domain.Provider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ProviderRepository extends JpaRepository<Provider, String> {

    /** Loads a provider whose version is re-checked at commit, so a concurrent status change fails the transaction. */
    @Lock(LockModeType.OPTIMISTIC)
    @Query("select p from Provider p where p.id = :id")
    Optional<Provider> findForBookingById(String id);
}
