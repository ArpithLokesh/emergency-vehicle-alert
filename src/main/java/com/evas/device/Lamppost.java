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
 * A streetlamp fitted with the LiFi broadcaster hardware. The backend never
 * talks to a real device — it publishes an alert instruction to RabbitMQ
 * under {@code routingKey}; a real lamppost would consume from the queue
 * bound to that key. Maps 1:1 onto the {@code lamppost} table from
 * {@code V1__create_vehicle_and_device_tables.sql}.
 */
@Entity
@Table(name = "lamppost")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lamppost {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /**
     * WGS84 point, SRID 4326 — same as GPS/OSRM. Coordinate order inside
     * JTS/PostGIS is (lng, lat), not (lat, lng).
     */
    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Column(name = "routing_key", nullable = false, unique = true, length = 128)
    private String routingKey;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
