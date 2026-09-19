package com.evas.alerting;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code alerting.*} from application.yml. Same pattern as
 * {@link com.evas.trip.TrackingProperties} — constructor-bound record,
 * compact constructor supplies defaults, so tuning alerting behavior is a
 * config change, never a code change (locked pre-MVP-5; becomes a live
 * policy store then).
 */
@ConfigurationProperties(prefix = "alerting")
public record AlertingProperties(
        int signalWindowSize,
        int lamppostWindowSize,
        double signalTriggerDistanceMeters,
        double revertMarginMeters,
        double deviationThresholdMeters,
        double routeCorridorMeters,
        Duration safetyTimeout,
        Duration sweepInterval,
        Executor executor
) {
    public AlertingProperties {
        if (signalWindowSize <= 0) {
            signalWindowSize = 3;
        }
        if (lamppostWindowSize <= 0) {
            lamppostWindowSize = 8;
        }
        if (signalTriggerDistanceMeters <= 0) {
            signalTriggerDistanceMeters = 400;
        }
        if (revertMarginMeters <= 0) {
            revertMarginMeters = 30;
        }
        if (deviationThresholdMeters <= 0) {
            deviationThresholdMeters = 100;
        }
        if (routeCorridorMeters <= 0) {
            // How far off the route's centerline a device can be and still
            // count as "on this route" for window population — distinct
            // from deviationThresholdMeters (which is about the *vehicle*
            // straying from its route, not a *device*'s distance from it).
            routeCorridorMeters = 50;
        }
        if (safetyTimeout == null) {
            safetyTimeout = Duration.ofSeconds(120);
        }
        if (sweepInterval == null) {
            sweepInterval = Duration.ofSeconds(30);
        }
        if (executor == null) {
            executor = new Executor(0, 0, 0);
        }
    }

    /** Bounded pool sizing for the async alerting executor — see {@code AlertingExecutorConfig}. */
    public record Executor(int corePoolSize, int maxPoolSize, int queueCapacity) {
        public Executor {
            if (corePoolSize <= 0) {
                corePoolSize = 2;
            }
            if (maxPoolSize <= 0) {
                maxPoolSize = 4;
            }
            if (queueCapacity <= 0) {
                queueCapacity = 100;
            }
        }
    }
}
