package com.evas.trip;

import com.evas.common.GeoPointDto;
import com.evas.common.GeoPoints;
import java.time.Instant;
import java.util.UUID;

/**
 * Minimal acknowledgement returned to the vehicle after a position update:
 * its own trip state only — no device data. The vehicle reports where it is
 * and learns whether it should keep going ({@code EN_ROUTE}) or stop
 * ({@code ARRIVED}). See docs/SIMULATOR-API-CONTRACT.md (W3).
 */
public record PositionUpdateResponse(
        UUID tripId,
        TripStatus status,
        GeoPointDto lastKnownPosition,
        Instant completedAt
) {
    public static PositionUpdateResponse from(Trip trip) {
        return new PositionUpdateResponse(
                trip.getId(),
                trip.getStatus(),
                trip.getLastKnownPosition() != null ? GeoPoints.toDto(trip.getLastKnownPosition()) : null,
                trip.getCompletedAt());
    }
}
