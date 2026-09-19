package com.evas.alerting;

import com.evas.device.Lamppost;
import com.evas.trip.Trip;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Audit record of the first {@code LAMPPOST_APPROACH} broadcast to a given
 * lamppost for a given trip — fire-once per (trip, lamppost) for the whole
 * trip, not one row per position tick. Every subsequent tick while the
 * lamppost is in the window republishes {@code LAMPPOST_APPROACH} without
 * writing to this table; only the first sighting and the eventual
 * {@code LAMPPOST_CLEAR} matter for audit.
 */
@Entity
@Table(name = "lamppost_alert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LamppostAlert {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lamppost_id", nullable = false)
    private Lamppost lamppost;

    @Builder.Default
    @Column(name = "fired_at", nullable = false)
    private Instant firedAt = Instant.now();
}
