package com.evas.vehicle;

/** PATCH semantics: every field is optional — null means "leave unchanged". */
public record VehicleUpdateRequest(
        String callSign,
        VehicleStatus status
) {
}
