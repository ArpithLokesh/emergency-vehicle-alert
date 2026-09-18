package com.evas.trip;

import com.evas.common.GeoPointDto;
import com.evas.common.GeoPoints;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TripResponse(
        UUID id,
        UUID vehicleId,
        GeoPointDto origin,
        GeoPointDto destination,
        List<GeoPointDto> routeGeometry,
        TripStatus status,
        GeoPointDto lastKnownPosition,
        Instant createdAt,
        Instant completedAt
) {
    public static TripResponse from(Trip trip) {
        return new TripResponse(
                trip.getId(),
                trip.getVehicle().getId(),
                GeoPoints.toDto(trip.getOrigin()),
                GeoPoints.toDto(trip.getDestination()),
                GeoPoints.toDtoList(trip.getRouteGeometry()),
                trip.getStatus(),
                trip.getLastKnownPosition() != null ? GeoPoints.toDto(trip.getLastKnownPosition()) : null,
                trip.getCreatedAt(),
                trip.getCompletedAt());
    }
}
