package com.evas.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VehicleCreateRequest(
        @NotBlank String callSign,
        @NotNull VehicleType type
) {
}
