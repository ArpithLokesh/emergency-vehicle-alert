package com.evas.device;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LamppostRepository extends JpaRepository<Lamppost, UUID> {

    List<Lamppost> findByActive(boolean active);

    // Spatial "which lampposts lie near this route" queries land here in
    // MVP-1, alongside Routing — not needed for MVP-0's plain registry CRUD.
}
