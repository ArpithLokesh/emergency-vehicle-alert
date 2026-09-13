package com.evas.vehicle;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByType(VehicleType type);

    List<Vehicle> findByStatus(VehicleStatus status);

    List<Vehicle> findByTypeAndStatus(VehicleType type, VehicleStatus status);

    boolean existsByCallSign(String callSign);
}
