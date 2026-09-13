package com.evas.device;

import com.evas.common.GeoPointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TrafficSignalCreateRequest(
        @NotBlank String code,
        @NotNull @Valid GeoPointDto location
) {
}
