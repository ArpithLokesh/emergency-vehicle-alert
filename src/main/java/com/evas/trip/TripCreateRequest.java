package com.evas.trip;

import com.evas.common.GeoPointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TripCreateRequest(
        @NotNull UUID vehicleId,
        @NotNull @Valid GeoPointDto origin,
        @NotNull @Valid GeoPointDto destination
) {
}
