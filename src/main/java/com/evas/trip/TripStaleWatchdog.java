package com.evas.trip;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The backend is otherwise purely reactive (it acts when the vehicle POSTs a
 * position). But a vehicle that stops transmitting sends nothing — and
 * detecting *silence* is the one thing you can't do reactively. So this is
 * the single justified scheduled job: it periodically sweeps for active
 * trips whose feed has gone quiet and marks them STALE (freeing the vehicle),
 * so a dropped feed can't strand a trip/vehicle forever.
 *
 * <p>Marking is delegated per-trip to {@link TripService#markStale} (a
 * separate bean, so its @Transactional applies) — one transaction per trip,
 * so one bad row can't abort the whole sweep.
 */
@Component
@RequiredArgsConstructor
public class TripStaleWatchdog {

    private static final Logger log = LoggerFactory.getLogger(TripStaleWatchdog.class);

    private final TripRepository tripRepository;
    private final TripService tripService;
    private final TrackingProperties trackingProperties;

    @Scheduled(fixedDelayString = "${tracking.stale-check-interval}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(trackingProperties.staleTimeout());
        List<Trip> stale = tripRepository.findStale(cutoff);
        if (stale.isEmpty()) {
            return;
        }
        for (Trip trip : stale) {
            try {
                tripService.markStale(trip.getId());
            } catch (Exception e) {
                log.warn("Failed to mark trip {} STALE, will retry next sweep", trip.getId(), e);
            }
        }
        log.info("Stall watchdog marked {} trip(s) STALE (no position for > {})",
                stale.size(), trackingProperties.staleTimeout());
    }
}
