package com.evas.trip;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
