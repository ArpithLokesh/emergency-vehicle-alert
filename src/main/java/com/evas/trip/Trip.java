package com.evas.trip;

import com.evas.vehicle.Vehicle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

/**
 * One emergency run from an origin to a destination. Trip creation is
 * fail-fast: a row only ever exists once OSRM routing has already
 * succeeded, so route fields are never null. Maps 1:1 onto the {@code trip}
 * table from {@code V2__create_trip_table.sql}.
 */
@Entity
@Table(name = "trip")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trip {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point origin;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point destination;

    @Column(name = "route_geometry", nullable = false, columnDefinition = "geometry(LineString,4326)")
    private LineString routeGeometry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TripStatus status;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
