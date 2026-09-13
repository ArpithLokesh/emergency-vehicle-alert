package com.evas.common;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * The JSON shape a location takes over REST: plain {@code {"lat": ..,
 * "lng": ..}}, not GeoJSON — decided in docs/mvp-0/LLD-data-model.md since
 * this project owns both ends of the API. Converted to/from a JTS
 * {@link org.locationtech.jts.geom.Point} via {@link GeoPoints}.
 */
public record GeoPointDto(

        @NotNull
        @DecimalMin(value = "-90", message = "must be >= -90")
        @DecimalMax(value = "90", message = "must be <= 90")
        Double lat,

        @NotNull
        @DecimalMin(value = "-180", message = "must be >= -180")
        @DecimalMax(value = "180", message = "must be <= 180")
        Double lng
) {
}
