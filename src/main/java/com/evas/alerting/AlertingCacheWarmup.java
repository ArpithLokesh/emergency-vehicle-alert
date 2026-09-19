package com.evas.alerting;

import com.evas.trip.Trip;
import com.evas.trip.TripRepository;
import com.evas.trip.TripStatus;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds {@link TripAlertingCache} for every currently-active trip on
 * backend restart. The cache is in-memory only, so a restart loses it —
 * but since it's always rebuildable from the trip's own route and current
 * position (see {@link TripWindowBuilder}), a cold cache after a restart
 * is not a special case elsewhere; this just does it proactively instead
 * of waiting for each trip's next position tick to trigger a lazy rebuild.
 *
 * <p>STALE trips are included, not just EN_ROUTE — they're paused, not
 * dead, and may resume at any time (see {@code TripService#updatePosition}).
 */
@Component
@RequiredArgsConstructor
public class AlertingCacheWarmup {

    private static final Logger log = LoggerFactory.getLogger(AlertingCacheWarmup.class);

    private final TripRepository tripRepository;
    private final TripAlertingCache cache;
    private final TripWindowBuilder windowBuilder;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        List<Trip> activeTrips = new ArrayList<>(tripRepository.findByStatus(TripStatus.EN_ROUTE));
        activeTrips.addAll(tripRepository.findByStatus(TripStatus.STALE));

        int rebuilt = 0;
        for (Trip trip : activeTrips) {
            try {
                cache.put(trip.getId(), windowBuilder.buildFresh(trip));
                rebuilt++;
            } catch (Exception e) {
                log.warn("Failed to rebuild alerting cache for trip {} on startup", trip.getId(), e);
            }
        }
        log.info("Alerting cache warm-up: rebuilt {} of {} active trip window(s)", rebuilt, activeTrips.size());
    }
}
