package com.evas.alerting;

import java.time.Instant;
import java.util.UUID;

/** Single-shot revert-to-normal command. See docs/SIMULATOR-API-CONTRACT.md W7a. */
public record SignalPreemptionRevertMessage(
        String type,
        UUID tripId,
        UUID signalId,
        RevertReason reason,
        Instant issuedAt
) {
    public static SignalPreemptionRevertMessage of(UUID tripId, UUID signalId, RevertReason reason) {
        return new SignalPreemptionRevertMessage("SIGNAL_PREEMPTION_REVERT", tripId, signalId, reason, Instant.now());
    }
}
