package com.evas.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.locationtech.jts.geom.Point;

/**
 * A controllable traffic signal at a junction. Deliberately minimal —
 * approach-direction metadata, the signal state machine, and any
 * command-delivery field are designed later, alongside the Preemption
 * engine (MVP-3). See docs/mvp-0/LLD-data-model.md.
 */
@Entity
@Table(name = "traffic_signal")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrafficSignal {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
