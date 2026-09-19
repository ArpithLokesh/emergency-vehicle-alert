package com.evas.alerting;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LamppostAlertRepository extends JpaRepository<LamppostAlert, UUID> {

    boolean existsByTripIdAndLamppostId(UUID tripId, UUID lamppostId);
}
