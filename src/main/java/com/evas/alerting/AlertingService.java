package com.evas.alerting;

import com.evas.device.Lamppost;
import com.evas.device.LamppostRepository;
import com.evas.device.TrafficSignal;
import com.evas.device.TrafficSignalRepository;
import com.evas.routing.RoutingException;
import com.evas.routing.RoutingService;
import com.evas.trip.Trip;
import com.evas.trip.TripRepository;
import com.evas.trip.TripStatus;
import com.evas.trip.events.TripTerminationReason;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alerting engine. Two entry points, both driven by {@code TripService}
 * events (see {@link AlertingListener}/{@link RevertListener}):
 * {@link #evaluate} reacts to every position update, {@link #handleTripReset}
 * reacts to a trip stopping (for good or for now — see
 * {@link TripTerminationReason}).
 *
 * <p>Both hold their own {@code @Transactional} boundary distinct from
 * whatever wrote the position — they only ever run after that write has
 * already committed (async, post-commit listeners), so nothing here can
 * roll back or delay position ingestion.
 */
@Service
@RequiredArgsConstructor
public class AlertingService {

    private static final Logger log = LoggerFactory.getLogger(AlertingService.class);

    private final TripRepository tripRepository;
    private final TrafficSignalRepository trafficSignalRepository;
    private final LamppostRepository lamppostRepository;
    private final SignalPreemptionRepository signalPreemptionRepository;
    private final LamppostAlertRepository lamppostAlertRepository;
    private final TripAlertingCache cache;
    private final TripWindowBuilder windowBuilder;
    private final CommandPublisher commandPublisher;
    private final RoutingService routingService;
    private final AlertingProperties properties;

    /**
     * Reacts to one position update: keeps the device windows current
     * (deviation re-route, pop-passed/refill), and fires signal
     * ACTIVATE/lamppost APPROACH for whatever's now in trigger range.
     *
     * <p>Takes the same pessimistic lock {@code TripService} uses for its
     * own check-then-write paths on Trip, for the whole method — this is
     * what makes the window's check-then-insert-preemption logic safe
     * without needing per-row locks on {@code signal_preemption}: only one
     * {@code evaluate} (or {@code TripService} write) can be in flight for
     * a given trip at a time, so two rapid position ticks can't both decide
     * to activate the same signal.
     */
    @Transactional
    public void evaluate(UUID tripId) {
        Trip trip = tripRepository.findByIdForUpdate(tripId).orElse(null);
        if (trip == null || trip.getStatus() != TripStatus.EN_ROUTE) {
            // Race with a terminal/pause transition that landed first — that
            // transition's own TripTerminatedEvent already handles cleanup.
            return;
        }
        Point position = trip.getLastKnownPosition();
        if (position == null) {
            return;
        }

        trip = handleDeviationIfAny(trip, position);

        RouteContext ctx = routeContext(trip, position);
        Trip finalTrip = trip;
        TripAlertingState state = cache.get(tripId).orElseGet(() -> windowBuilder.buildFresh(finalTrip));

        state = shiftAndRevertSignals(trip, state, ctx);
        state = shiftAndClearLampposts(trip, state, ctx);

        activateSignals(trip, state, ctx);
        approachLampposts(trip, state, ctx);

        cache.put(tripId, state);
    }

    /**
     * Reacts to a trip no longer being actively tracked — reverts every
     * ACTIVE signal preemption for the trip and clears every lamppost
     * currently in its window.
     *
     * <p>Signal reverts are DB-authoritative, not cache-driven: they query
     * {@code signal_preemption} directly for anything still ACTIVE, rather
     * than only reverting what the in-memory window cache currently knows
     * about. This matters because the cache can legitimately be cold when
     * this fires — e.g. right after a backend restart, if the safety-net
     * watchdog's first sweep happens to run before {@code AlertingCacheWarmup}
     * finishes rebuilding caches for trips that were already overdue at
     * boot (the two have no defined ordering relative to each other). A
     * safety-critical revert must not silently no-op just because an
     * operational cache happens to be empty. Lampposts have no such
     * DB-tracked "currently active" state to fall back on (their audit
     * table is fire-once, not a state machine) — CLEAR stays best-effort
     * from whatever the cache holds, which is fine since a missed CLEAR is
     * not a safety issue the way a stuck-green signal is.
     *
     * <p>Doesn't take the trip lock {@link #evaluate} does: it never writes
     * to the {@code trip} table, only to {@code signal_preemption}, and
     * {@link SignalPreemptionRepository#revertIfActive} is itself an atomic
     * conditional update — safe against a concurrent revert from
     * {@link #evaluate}'s pass-detection or the safety-timeout sweep landing
     * at the same time.
     */
    @Transactional
    public void handleTripReset(UUID tripId, TripTerminationReason reason) {
        RevertReason revertReason = mapReason(reason);

        for (SignalPreemption preemption : signalPreemptionRepository.findByTripIdAndState(tripId, SignalPreemptionState.ACTIVE)) {
            revertSignalIfActive(tripId, preemption.getSignal().getId(), revertReason);
        }

        cache.get(tripId).ifPresent(state -> {
            for (UUID lamppostId : state.lamppostWindow()) {
                clearLamppost(tripId, lamppostId);
            }
        });

        if (reason != TripTerminationReason.PAUSED) {
            cache.invalidate(tripId);
        }
        // PAUSED: cache entry (the window's device list) is left in place —
        // resume rebuilds nothing extra, evaluate() just re-activates
        // against the same tracked devices once positions resume.
    }

    private RevertReason mapReason(TripTerminationReason reason) {
        return switch (reason) {
            case PAUSED -> RevertReason.TRIP_PAUSED;
            case CANCELLED, ARRIVED, EXPIRED -> RevertReason.TRIP_TERMINATED;
        };
    }

    // ---- deviation ----------------------------------------------------

    private Trip handleDeviationIfAny(Trip trip, Point position) {
        double deviationMeters = tripRepository.distanceToRouteMeters(trip.getRouteGeometry(), position);
        if (deviationMeters <= properties.deviationThresholdMeters()) {
            return trip;
        }

        LineString newRoute;
        try {
            newRoute = routingService.getRoute(
                    position.getY(), position.getX(), trip.getDestination().getY(), trip.getDestination().getX());
        } catch (RoutingException e) {
            log.warn("Trip {} deviated {}m from its route but re-route failed; continuing with the existing route this tick",
                    trip.getId(), Math.round(deviationMeters), e);
            return trip;
        }

        TripAlertingState oldState = cache.get(trip.getId()).orElse(null);
        trip.setRouteGeometry(newRoute);
        trip = tripRepository.save(trip);
        TripAlertingState newState = windowBuilder.buildFresh(trip);

        if (oldState != null) {
            for (UUID signalId : oldState.signalWindow()) {
                if (!newState.signalWindow().contains(signalId)) {
                    revertSignalIfActive(trip.getId(), signalId, RevertReason.WINDOW_CHANGED);
                }
            }
            for (UUID lamppostId : oldState.lamppostWindow()) {
                if (!newState.lamppostWindow().contains(lamppostId)) {
                    clearLamppost(trip.getId(), lamppostId);
                }
            }
        }

        cache.put(trip.getId(), newState);
        log.info("Trip {} deviated {}m from route, re-routed and rebuilt device windows",
                trip.getId(), Math.round(deviationMeters));
        return trip;
    }

    // ---- signals --------------------------------------------------------

    private TripAlertingState shiftAndRevertSignals(Trip trip, TripAlertingState state, RouteContext ctx) {
        List<UUID> window = new ArrayList<>(state.signalWindow());
        int poppedCount = 0;

        var it = window.iterator();
        while (it.hasNext()) {
            UUID signalId = it.next();
            TrafficSignal signal = trafficSignalRepository.findById(signalId).orElse(null);
            if (signal == null || !signal.isActive()) {
                if (signal != null) {
                    revertSignalIfActive(trip.getId(), signalId, RevertReason.WINDOW_CHANGED);
                }
                it.remove();
                poppedCount++;
                continue;
            }
            double aheadMeters = aheadDistanceMeters(ctx, signal.getLocation());
            if (aheadMeters < -properties.revertMarginMeters()) {
                revertSignalIfActive(trip.getId(), signalId, RevertReason.PASSED);
                it.remove();
                poppedCount++;
            }
        }

        for (int i = 0; i < poppedCount; i++) {
            Point anchor = window.isEmpty() ? ctx.position() : locationOfSignal(window.get(window.size() - 1));
            windowBuilder.nextSignalAfter(ctx.route(), anchor)
                    .filter(id -> !window.contains(id))
                    .ifPresent(window::add);
        }

        return new TripAlertingState(window, state.lamppostWindow());
    }

    private void activateSignals(Trip trip, TripAlertingState state, RouteContext ctx) {
        for (UUID signalId : state.signalWindow()) {
            TrafficSignal signal = trafficSignalRepository.findById(signalId).orElse(null);
            if (signal == null || !signal.isActive()) {
                continue;
            }
            double aheadMeters = aheadDistanceMeters(ctx, signal.getLocation());
            if (aheadMeters < 0 || aheadMeters > properties.signalTriggerDistanceMeters()) {
                continue;
            }
            boolean alreadyActive = signalPreemptionRepository
                    .findByTripIdAndSignalIdAndState(trip.getId(), signalId, SignalPreemptionState.ACTIVE)
                    .isPresent();
            if (alreadyActive) {
                continue;
            }
            signalPreemptionRepository.save(SignalPreemption.builder()
                    .trip(trip)
                    .signal(signal)
                    .state(SignalPreemptionState.ACTIVE)
                    .build());
            commandPublisher.publish(signal.getRoutingKey(),
                    SignalPreemptionActivateMessage.of(trip.getId(), signalId, aheadMeters, trip.getVehicle().getType()));
        }
    }

    private void revertSignalIfActive(UUID tripId, UUID signalId, RevertReason reason) {
        signalPreemptionRepository.findByTripIdAndSignalIdAndState(tripId, signalId, SignalPreemptionState.ACTIVE)
                .ifPresent(preemption -> {
                    int updated = signalPreemptionRepository.revertIfActive(preemption.getId(), reason);
                    if (updated > 0) {
                        trafficSignalRepository.findById(signalId).ifPresent(signal ->
                                commandPublisher.publish(signal.getRoutingKey(),
                                        SignalPreemptionRevertMessage.of(tripId, signalId, reason)));
                    }
                });
    }

    private Point locationOfSignal(UUID signalId) {
        return trafficSignalRepository.findById(signalId).map(TrafficSignal::getLocation).orElse(null);
    }

    // ---- lampposts --------------------------------------------------------

    private TripAlertingState shiftAndClearLampposts(Trip trip, TripAlertingState state, RouteContext ctx) {
        List<UUID> window = new ArrayList<>(state.lamppostWindow());
        int poppedCount = 0;

        var it = window.iterator();
        while (it.hasNext()) {
            UUID lamppostId = it.next();
            Lamppost lamppost = lamppostRepository.findById(lamppostId).orElse(null);
            if (lamppost == null || !lamppost.isActive()) {
                if (lamppost != null) {
                    commandPublisher.publish(lamppost.getRoutingKey(), LamppostClearMessage.of(trip.getId(), lamppostId));
                }
                it.remove();
                poppedCount++;
                continue;
            }
            double aheadMeters = aheadDistanceMeters(ctx, lamppost.getLocation());
            if (aheadMeters < -properties.revertMarginMeters()) {
                commandPublisher.publish(lamppost.getRoutingKey(), LamppostClearMessage.of(trip.getId(), lamppostId));
                it.remove();
                poppedCount++;
            }
        }

        for (int i = 0; i < poppedCount; i++) {
            Point anchor = window.isEmpty() ? ctx.position() : locationOfLamppost(window.get(window.size() - 1));
            windowBuilder.nextLamppostAfter(ctx.route(), anchor)
                    .filter(id -> !window.contains(id))
                    .ifPresent(window::add);
        }

        return new TripAlertingState(state.signalWindow(), window);
    }

    private void approachLampposts(Trip trip, TripAlertingState state, RouteContext ctx) {
        for (UUID lamppostId : state.lamppostWindow()) {
            Lamppost lamppost = lamppostRepository.findById(lamppostId).orElse(null);
            if (lamppost == null || !lamppost.isActive()) {
                continue;
            }
            double aheadMeters = aheadDistanceMeters(ctx, lamppost.getLocation());
            if (aheadMeters < 0) {
                continue;
            }
            if (!lamppostAlertRepository.existsByTripIdAndLamppostId(trip.getId(), lamppostId)) {
                lamppostAlertRepository.save(LamppostAlert.builder().trip(trip).lamppost(lamppost).build());
            }
            commandPublisher.publish(lamppost.getRoutingKey(),
                    LamppostApproachMessage.of(trip.getId(), lamppostId, aheadMeters, trip.getVehicle().getType()));
        }
    }

    private void clearLamppost(UUID tripId, UUID lamppostId) {
        lamppostRepository.findById(lamppostId).ifPresent(lamppost ->
                commandPublisher.publish(lamppost.getRoutingKey(), LamppostClearMessage.of(tripId, lamppostId)));
    }

    private Point locationOfLamppost(UUID lamppostId) {
        return lamppostRepository.findById(lamppostId).map(Lamppost::getLocation).orElse(null);
    }

    // ---- shared route math ----------------------------------------------

    private RouteContext routeContext(Trip trip, Point position) {
        double vehicleFraction = tripRepository.fractionAlongRoute(trip.getRouteGeometry(), position);
        double routeLengthMeters = tripRepository.routeLengthMeters(trip.getRouteGeometry());
        return new RouteContext(trip.getRouteGeometry(), position, vehicleFraction, routeLengthMeters);
    }

    /** Along-route distance from the vehicle to {@code deviceLocation}; negative means behind the vehicle. */
    private double aheadDistanceMeters(RouteContext ctx, Point deviceLocation) {
        double deviceFraction = tripRepository.fractionAlongRoute(ctx.route(), deviceLocation);
        return (deviceFraction - ctx.vehicleFraction()) * ctx.routeLengthMeters();
    }

    /** Per-evaluate-tick constants, computed once so N device checks don't each recompute the vehicle's own fraction/route length. */
    private record RouteContext(LineString route, Point position, double vehicleFraction, double routeLengthMeters) {
    }
}
