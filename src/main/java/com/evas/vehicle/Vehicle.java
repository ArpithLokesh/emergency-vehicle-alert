package com.evas.vehicle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Maps 1:1 onto the {@code vehicle} table created by
 * {@code V1__create_vehicle_and_device_tables.sql} — column names, types,
 * and constraints here must stay in sync with that migration, since
 * Hibernate only validates the schema (ddl-auto=validate), never generates
 * it.
 */
@Entity
@Table(name = "vehicle")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "call_sign", nullable = false, unique = true, length = 32)
    private String callSign;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VehicleType type;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 16)
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
