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
 * the single justified scheduled job: it periodically sweeps for trips
 * whose feed has gone quiet and moves them through the two-tier stall
 * model — a short soft timeout that pauses the trip (STALE, recoverable),
 * and a longer hard timeout that ends it for good (EXPIRED, frees the
 * vehicle) — so neither a brief GPS blackout nor a genuinely dead feed can
 * strand a trip/vehicle.
 *
 * <p>Expiry is checked before staleness on each sweep: a trip that crosses
 * both cutoffs before ever being checked (e.g. after a long scheduler
 * delay) should go straight to EXPIRED, not flicker through STALE first.
 * Marking is delegated per-trip to {@link TripService} (a separate bean,
 * so its @Transactional actually applies) — one transaction per trip, so
 * one bad row can't abort the sweep.
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
        Instant expireCutoff = Instant.now().minus(trackingProperties.expireTimeout());
        List<Trip> toExpire = tripRepository.findOverdueForExpiry(expireCutoff);
        for (Trip trip : toExpire) {
            try {
                tripService.markExpired(trip.getId(), expireCutoff);
            } catch (Exception e) {
                log.warn("Failed to mark trip {} EXPIRED, will retry next sweep", trip.getId(), e);
            }
        }
        if (!toExpire.isEmpty()) {
            log.info("Stall watchdog marked {} trip(s) EXPIRED (no position for > {})",
                    toExpire.size(), trackingProperties.expireTimeout());
        }

        Instant staleCutoff = Instant.now().minus(trackingProperties.staleTimeout());
        List<Trip> toStale = tripRepository.findOverdueForStale(staleCutoff);
        for (Trip trip : toStale) {
            try {
                tripService.markStale(trip.getId(), staleCutoff);
            } catch (Exception e) {
                log.warn("Failed to mark trip {} STALE, will retry next sweep", trip.getId(), e);
            }
        }
        if (!toStale.isEmpty()) {
            log.info("Stall watchdog paused {} trip(s) as STALE (no position for > {})",
                    toStale.size(), trackingProperties.staleTimeout());
        }
    }
}
