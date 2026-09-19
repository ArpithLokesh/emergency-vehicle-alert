package com.evas.alerting;

import com.evas.device.Lamppost;
import com.evas.device.LamppostRepository;
import com.evas.device.TrafficSignal;
import com.evas.device.TrafficSignalRepository;
import com.evas.trip.Trip;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;

/**
 * Builds and refills the per-trip device windows that
 * {@link TripAlertingCache} holds, on top of
 * {@code TrafficSignalRepository}/{@code LamppostRepository}'s
 * next-along-route spatial queries.
 *
 * <p>Deliberately reactive, not eager: there's no "populate at trip
 * creation" step. {@link #buildFresh} is called wherever a cache entry
 * turns out to be missing — the first position update on a new trip, a
 * resume from STALE, or a backend-restart warm-up ({@code
 * AlertingCacheWarmup}) — all the same code path, anchored on whatever
 * position the trip currently has (falling back to its origin if no
 * position has arrived yet). This avoids a special case for "just
 * created" that would otherwise need to live in {@code TripService},
 * which would pull an alerting-package dependency into the trip package.
 */
@Component
@RequiredArgsConstructor
public class TripWindowBuilder {

    private final TrafficSignalRepository trafficSignalRepository;
    private final LamppostRepository lamppostRepository;
    private final AlertingProperties properties;

    public TripAlertingState buildFresh(Trip trip) {
        Point position = anchorPosition(trip);
        LineString route = trip.getRouteGeometry();

        List<UUID> signalWindow = trafficSignalRepository
                .findNextAlongRoute(route, position, properties.routeCorridorMeters(), properties.signalWindowSize())
                .stream().map(TrafficSignal::getId).toList();

        List<UUID> lamppostWindow = lamppostRepository
                .findNextAlongRoute(route, position, properties.routeCorridorMeters(), properties.lamppostWindowSize())
                .stream().map(Lamppost::getId).toList();

        return new TripAlertingState(signalWindow, lamppostWindow);
    }

    /** The next signal beyond {@code afterPosition} along {@code route}, if any — used to refill a popped slot. */
    public Optional<UUID> nextSignalAfter(LineString route, Point afterPosition) {
        return trafficSignalRepository
                .findNextAlongRoute(route, afterPosition, properties.routeCorridorMeters(), 1)
                .stream().findFirst().map(TrafficSignal::getId);
    }

    /** The next lamppost beyond {@code afterPosition} along {@code route}, if any — used to refill a popped slot. */
    public Optional<UUID> nextLamppostAfter(LineString route, Point afterPosition) {
        return lamppostRepository
                .findNextAlongRoute(route, afterPosition, properties.routeCorridorMeters(), 1)
                .stream().findFirst().map(Lamppost::getId);
    }

    private Point anchorPosition(Trip trip) {
        return trip.getLastKnownPosition() != null ? trip.getLastKnownPosition() : trip.getOrigin();
    }
}
