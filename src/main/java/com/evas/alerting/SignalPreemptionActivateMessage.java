package com.evas.alerting;

import com.evas.vehicle.VehicleType;
import java.time.Instant;
import java.util.UUID;

/** Single-shot force-green command. See docs/SIMULATOR-API-CONTRACT.md W7a. */
public record SignalPreemptionActivateMessage(
        String type,
        UUID tripId,
        UUID signalId,
        double distanceM,
        VehicleType vehicleType,
        Instant issuedAt
) {
    public static SignalPreemptionActivateMessage of(UUID tripId, UUID signalId, double distanceM, VehicleType vehicleType) {
        return new SignalPreemptionActivateMessage(
                "SIGNAL_PREEMPTION_ACTIVATE", tripId, signalId, distanceM, vehicleType, Instant.now());
    }
}
