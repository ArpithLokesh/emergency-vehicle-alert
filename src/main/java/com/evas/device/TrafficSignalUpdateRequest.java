package com.evas.device;

import com.evas.common.GeoPointDto;

/** PATCH semantics: every field is optional — null means "leave unchanged". */
public record TrafficSignalUpdateRequest(
        String code,
        GeoPointDto location,
        String routingKey,
        Boolean active
) {
}
