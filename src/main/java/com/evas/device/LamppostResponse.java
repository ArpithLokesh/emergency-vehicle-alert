package com.evas.device;

import com.evas.common.GeoPointDto;
import com.evas.common.GeoPoints;
import java.util.UUID;

public record LamppostResponse(
        UUID id,
        String code,
        GeoPointDto location,
        String routingKey,
        boolean active
) {
    public static LamppostResponse from(Lamppost lamppost) {
        return new LamppostResponse(
                lamppost.getId(),
                lamppost.getCode(),
                GeoPoints.toDto(lamppost.getLocation()),
                lamppost.getRoutingKey(),
                lamppost.isActive());
    }
}
