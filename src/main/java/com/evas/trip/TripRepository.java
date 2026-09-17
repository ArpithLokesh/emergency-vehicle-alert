package com.evas.trip;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface    TripRepository extends JpaRepository<Trip, UUID> {

    List<Trip> findByStatus(TripStatus status);

    // "VehicleId" resolves to the nested vehicle.id property automatically
    // — Trip has no field literally named vehicleId, but it does have a
    // `vehicle` association, so Spring Data's query-derivation walks into it.
    boolean existsByVehicleIdAndStatus(UUID vehicleId, TripStatus status);
}
