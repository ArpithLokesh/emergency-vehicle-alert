package com.evas.alerting;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SignalPreemptionRepository extends JpaRepository<SignalPreemption, UUID> {

    Optional<SignalPreemption> findByTripIdAndSignalIdAndState(UUID tripId, UUID signalId, SignalPreemptionState state);

    /** Every currently-ACTIVE preemption for a trip — used by the pause/terminal fan-out. */
    List<SignalPreemption> findByTripIdAndState(UUID tripId, SignalPreemptionState state);

    /** ACTIVE preemptions stuck past the safety-timeout cutoff — used by the sweep. */
    List<SignalPreemption> findByStateAndForceGreenAtBefore(SignalPreemptionState state, Instant cutoff);

    /**
     * Atomic conditional revert: flips ACTIVE -> REVERTED only if it's still
     * ACTIVE, returning the number of rows changed (0 or 1). Needed because
     * three independent paths can all try to revert the same row — pass
     * detection (in {@code evaluate}), the terminal/pause fan-out, and the
     * safety-timeout sweep — and only one should "win" and publish the
     * revert command. A plain load-check-save here would race the same way
     * {@code Trip}'s check-then-write paths did before the pessimistic-lock
     * fix (see {@code TripRepository#findByIdForUpdate}); the atomic UPDATE
     * sidesteps that without needing a row lock on every read.
     */
    @Modifying
    @Query("UPDATE SignalPreemption sp SET sp.state = com.evas.alerting.SignalPreemptionState.REVERTED, "
            + "sp.revertReason = :reason, sp.revertedAt = CURRENT_TIMESTAMP "
            + "WHERE sp.id = :id AND sp.state = com.evas.alerting.SignalPreemptionState.ACTIVE")
    int revertIfActive(@Param("id") UUID id, @Param("reason") RevertReason reason);
}
