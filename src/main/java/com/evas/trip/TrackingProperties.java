package com.evas.trip;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code tracking.*} from application.yml. Same pattern as
 * {@link com.evas.routing.RoutingProperties} — constructor-bound record,
 * compact constructor supplies defaults, so tuning arrival/stall behavior
 * for a different environment is a config change, never a code change.
 */
@ConfigurationProperties(prefix = "tracking")
public record TrackingProperties(
        double arrivalThresholdMeters,
        Duration staleTimeout,
        Duration staleCheckInterval
) {
    public TrackingProperties {
        if (arrivalThresholdMeters <= 0) {
            arrivalThresholdMeters = 30;
        }
        if (staleTimeout == null) {
            staleTimeout = Duration.ofSeconds(45);
        }
        if (staleCheckInterval == null) {
            staleCheckInterval = Duration.ofSeconds(15);
        }
    }
}
