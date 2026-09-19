package com.evas.routing;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code routing.*} section of application.yml. {@code @ConfigurationProperties}
 * on a record gets constructor-bound automatically (enabled by
 * {@code @ConfigurationPropertiesScan} on the main application class) —
 * no {@code @Value} per field, one typed object instead. Spring Boot
 * parses simple duration strings like {@code "5s"}/{@code "24h"} into
 * {@link Duration} for free.
 *
 * <p>The compact constructor below supplies defaults for anything YAML
 * doesn't set — so swapping to a different OSRM server only ever means
 * editing application.yml, never this class.
 *
 * <p>{@code @Validated} + {@code @NotBlank} makes a missing/blank
 * {@code osrm-base-url} fail application startup with a clear message,
 * instead of booting fine and only failing obscurely on the first trip
 * creation request.
 */
@Validated
@ConfigurationProperties(prefix = "routing")
public record RoutingProperties(
        @NotBlank String osrmBaseUrl,
        String profile,
        Duration connectTimeout,
        Duration readTimeout
) {
    public RoutingProperties {
        if (profile == null) {
            profile = "driving";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(5);
        }
    }
}
