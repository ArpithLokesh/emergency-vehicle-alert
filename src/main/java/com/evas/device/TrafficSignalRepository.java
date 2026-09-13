package com.evas.device;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrafficSignalRepository extends JpaRepository<TrafficSignal, UUID> {

    List<TrafficSignal> findByActive(boolean active);
}
