package com.evas.routing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code caching.osrm-routes.*} — see {@link RoutingProperties} for how this binding works. */
@ConfigurationProperties(prefix = "caching.osrm-routes")
public record OsrmCacheProperties(
        Long maximumSize,
        Duration expireAfterWrite
) {
    public OsrmCacheProperties {
        if (maximumSize == null) {
            maximumSize = 500L;
        }
        if (expireAfterWrite == null) {
            expireAfterWrite = Duration.ofHours(24);
        }
    }
}
