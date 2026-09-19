package com.evas.trip;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface    TripRepository extends JpaRepository<Trip, UUID> {

    List<Trip> findByStatus(TripStatus status);

    // "VehicleId" resolves to the nested vehicle.id property automatically
    // — Trip has no field literally named vehicleId, but it does have a
    // `vehicle` association, so Spring Data's query-derivation walks into it.
    boolean existsByVehicleIdAndStatus(UUID vehicleId, TripStatus status);

    /**
     * Active trips whose feed has gone silent past {@code cutoff}. COALESCE
     * falls back to createdAt so a trip that never sent a first position is
     * caught too — can't be expressed as a derived method name, hence JPQL.
     */
    @Query("SELECT t FROM Trip t WHERE t.status = com.evas.trip.TripStatus.EN_ROUTE "
            + "AND COALESCE(t.lastPositionAt, t.createdAt) < :cutoff")
    List<Trip> findStale(@Param("cutoff") Instant cutoff);

    /**
     * Locking read for check-then-write paths on a Trip row (updatePosition,
     * markStale, cancel). PESSIMISTIC_WRITE translates to
     * {@code SELECT ... FOR UPDATE} in Postgres — any concurrent transaction
     * trying to acquire the same lock (via this method) or write the row
     * blocks until this transaction commits or rolls back.
     *
     * <p>The reason it exists: without it, two check-then-write callers on
     * the same trip (say the stall watchdog and a live position POST) can
     * both read {@code status == EN_ROUTE}, both compute their write, and
     * clobber each other on save() — leaving the trip and its vehicle in
     * inconsistent states. Serializing via the row lock removes the race.
     *
     * <p>Only used for writes — plain reads keep going through {@code findById}
     * so we don't add contention for no gain.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") UUID id);
}
