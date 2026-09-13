package com.evas.vehicle;

import java.time.Instant;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String callSign,
        VehicleType type,
        VehicleStatus status,
        Instant createdAt
) {
    public static VehicleResponse from(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getCallSign(),
                vehicle.getType(),
                vehicle.getStatus(),
                vehicle.getCreatedAt());
    }
}
