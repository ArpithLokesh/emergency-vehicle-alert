package com.evas.device;

import com.evas.common.GeoPointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LamppostCreateRequest(
        @NotBlank String code,
        @NotNull @Valid GeoPointDto location,
        @NotBlank String routingKey
) {
}
