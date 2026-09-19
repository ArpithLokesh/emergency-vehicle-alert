package com.evas.alerting;

import com.evas.vehicle.VehicleType;
import java.time.Instant;
import java.util.UUID;

/** Sent every position tick while a lamppost is in the trip's window. See docs/SIMULATOR-API-CONTRACT.md W7b. */
public record LamppostApproachMessage(
        String type,
        UUID tripId,
        UUID lamppostId,
        double distanceM,
        VehicleType vehicleType,
        Instant issuedAt
) {
    public static LamppostApproachMessage of(UUID tripId, UUID lamppostId, double distanceM, VehicleType vehicleType) {
        return new LamppostApproachMessage("LAMPPOST_APPROACH", tripId, lamppostId, distanceM, vehicleType, Instant.now());
    }
}
