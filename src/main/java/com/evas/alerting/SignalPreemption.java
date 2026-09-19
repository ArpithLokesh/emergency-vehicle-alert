package com.evas.alerting;

import com.evas.device.TrafficSignal;
import com.evas.trip.Trip;
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

/**
 * One force-green/revert cycle for a single (trip, signal) pair. A trip
 * that pauses (STALE) and resumes can legitimately produce multiple rows
 * for the same pair — the DB only enforces "at most one ACTIVE at a time"
 * (a partial unique index, not a plain unique constraint), so each
 * approach cycle is its own audit row. See
 * {@code V5__add_alerting_tables.sql}.
 */
@Entity
@Table(name = "signal_preemption")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignalPreemption {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signal_id", nullable = false)
    private TrafficSignal signal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SignalPreemptionState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "revert_reason", length = 32)
    private RevertReason revertReason;

    @Builder.Default
    @Column(name = "force_green_at", nullable = false)
    private Instant forceGreenAt = Instant.now();

    @Column(name = "reverted_at")
    private Instant revertedAt;
}
