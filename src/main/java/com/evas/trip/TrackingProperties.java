package com.evas.trip;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code tracking.*} from application.yml. Same pattern as
 * {@link com.evas.routing.RoutingProperties} — constructor-bound record,
 * compact constructor supplies defaults, so tuning arrival/stall behavior
 * for a different environment is a config change, never a code change.
 *
 * <p>Two-tier stall handling: {@code staleTimeout} is the soft threshold
 * (EN_ROUTE -> STALE, paused/recoverable); {@code expireTimeout} is the
 * hard threshold (-> EXPIRED, terminal). See {@link TripStatus}.
 */
@ConfigurationProperties(prefix = "tracking")
public record TrackingProperties(
        double arrivalThresholdMeters,
        Duration staleTimeout,
        Duration expireTimeout,
        Duration staleCheckInterval
) {
    public TrackingProperties {
        if (arrivalThresholdMeters <= 0) {
            arrivalThresholdMeters = 30;
        }
        if (staleTimeout == null) {
            staleTimeout = Duration.ofSeconds(120);
        }
        if (expireTimeout == null) {
            expireTimeout = Duration.ofSeconds(300);
        }
        if (staleCheckInterval == null) {
            staleCheckInterval = Duration.ofSeconds(15);
        }
    }
}
