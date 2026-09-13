package com.evas.device;

import com.evas.common.GeoPointDto;
import com.evas.common.GeoPoints;
import java.util.UUID;

public record TrafficSignalResponse(
        UUID id,
        String code,
        GeoPointDto location,
        boolean active
) {
    public static TrafficSignalResponse from(TrafficSignal signal) {
        return new TrafficSignalResponse(
                signal.getId(),
                signal.getCode(),
                GeoPoints.toDto(signal.getLocation()),
                signal.isActive());
    }
}
