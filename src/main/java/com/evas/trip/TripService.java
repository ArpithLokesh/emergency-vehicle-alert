package com.evas.trip;

import com.evas.common.ConflictException;
import com.evas.common.GeoPointDto;
import com.evas.common.GeoPoints;
import com.evas.common.NotFoundException;
import com.evas.routing.RoutingService;
import com.evas.vehicle.Vehicle;
import com.evas.vehicle.VehicleRepository;
import com.evas.vehicle.VehicleStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trip has actual business rules (vehicle availability, one-active-trip,
 * the OSRM call), unlike Vehicle/Lamppost/TrafficSignal's plain CRUD — this
 * is the first feature where a service layer between controller and
 * repository earns its place.
 */
@Service
@RequiredArgsConstructor
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);

    /** Statuses under which a vehicle is considered actively assigned to a trip. */
    private static final Set<TripStatus> ACTIVE_STATUSES = EnumSet.of(TripStatus.EN_ROUTE, TripStatus.STALE);

    private final TripRepository tripRepository;
    private final VehicleRepository vehicleRepository;
    private final RoutingService routingService;
    private final TrackingProperties trackingProperties;

    /**
     * @Transactional here matters: creating a trip touches two tables
     * (trip + vehicle). Without it, a failure between the two saves could
     * leave a Trip persisted with the vehicle still AVAILABLE — a stuck,
     * inconsistent state. With it, both writes commit together or neither
     * does.
     */
    @Transactional
    public Trip create(UUID vehicleId, GeoPointDto originDto, GeoPointDto destinationDto) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Vehicle " + vehicleId + " not found"));

        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            throw new ConflictException(
                    "Vehicle " + vehicleId + " is not AVAILABLE (current status: " + vehicle.getStatus() + ")");
        }
        if (tripRepository.existsByVehicleIdAndStatusIn(vehicleId, ACTIVE_STATUSES)) {
            throw new ConflictException("Vehicle " + vehicleId + " already has an active trip");
        }

        Point origin = GeoPoints.fromDto(originDto);
        Point destination = GeoPoints.fromDto(destinationDto);

        // Fail-fast: if OSRM throws, this propagates out of create() and
        // nothing below has run yet — no Trip row, no vehicle status change.
        LineString route = routingService.getRoute(origin.getY(), origin.getX(), destination.getY(), destination.getX());

        Trip trip = tripRepository.save(Trip.builder()
                .vehicle(vehicle)
                .origin(origin)
                .destination(destination)
                .routeGeometry(route)
                .status(TripStatus.EN_ROUTE)
                .build());

        vehicle.setStatus(VehicleStatus.ON_TRIP);
        vehicleRepository.save(vehicle);

        return trip;
    }

    /**
     * Ingests one raw GPS position for an active trip and checks for arrival.
     * Deliberately does no device/ETA work — that's alerting's concern
     * (MVP-3), reacting asynchronously to position changes, not this
     * request. Kept a plain point-to-point arrival check so the write path
     * runs no spatial query.
     *
     * <p>Accepts positions for a STALE trip too, not just EN_ROUTE — a
     * paused trip resumes transparently the moment its feed comes back
     * (real GPS drops happen for real reasons: tunnels, underpasses, dense
     * urban canyons). The client sees this as an ordinary 200 with
     * {@code status: EN_ROUTE}; there's no separate "resume" call.
     *
     * <p>Uses the locking read ({@link TripRepository#findByIdForUpdate})
     * because this is a check-then-write on the same row the stall watchdog
     * and cancel() also check-then-write — see that method's Javadoc.
     */
    @Transactional
    public Trip updatePosition(UUID id, GeoPointDto positionDto) {
        Trip trip = getForUpdate(id);
        if (!ACTIVE_STATUSES.contains(trip.getStatus())) {
            throw new ConflictException(
                    "Trip " + id + " is not accepting position updates (current status: " + trip.getStatus() + ")");
        }

        if (trip.getStatus() == TripStatus.STALE) {
            log.info("Trip {} resumed (position received after being STALE)", id);
            trip.setStatus(TripStatus.EN_ROUTE);
        }

        Point position = GeoPoints.fromDto(positionDto);
        trip.setLastKnownPosition(position);
        trip.setLastPositionAt(Instant.now());

        if (GeoPoints.distanceMeters(position, trip.getDestination())
                <= trackingProperties.arrivalThresholdMeters()) {
            trip.setStatus(TripStatus.ARRIVED);
            trip.setCompletedAt(Instant.now());
            freeVehicleIfOnTrip(trip);
        }

        return tripRepository.save(trip);
    }

    public Trip get(UUID id) {
        return tripRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Trip " + id + " not found"));
    }

    private Trip getForUpdate(UUID id) {
        return tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Trip " + id + " not found"));
    }

    public List<Trip> list(TripStatus status) {
        return status != null ? tripRepository.findByStatus(status) : tripRepository.findAll();
    }

    /**
     * Pauses a trip whose feed has gone silent past the soft timeout.
     * Deliberately NOT terminal: no completedAt, vehicle stays ON_TRIP — a
     * paused trip still owns its vehicle, and {@link #updatePosition} will
     * resume it the moment a position arrives again. The hard timeout
     * ({@link #markExpired}) is what actually ends an unrecovered trip.
     *
     * <p>Called per-trip by the watchdog (a separate bean, so this
     * @Transactional actually applies — a self-call would bypass the
     * proxy). Uses the locking read and re-checks both status and the
     * stall cutoff inside the transaction: a position may have committed
     * after the watchdog's scan but before this call acquired the row
     * lock, in which case the trip is no longer stale and must be left
     * alone (see TripRepository#findByIdForUpdate).
     */
    @Transactional
    public void markStale(UUID id, Instant cutoff) {
        Trip trip = tripRepository.findByIdForUpdate(id).orElse(null);
        if (trip == null || trip.getStatus() != TripStatus.EN_ROUTE) {
            return;
        }
        if (isFresherThan(trip, cutoff)) {
            return;
        }
        trip.setStatus(TripStatus.STALE);
        tripRepository.save(trip);
    }

    /**
     * Terminates a trip whose feed never resumed past the hard timeout.
     * Unlike {@link #markStale}, this is terminal: completedAt is set and
     * the vehicle is freed. Reachable from EN_ROUTE too (not just STALE) —
     * a sweep delayed long enough for a trip to cross both cutoffs at once
     * should go straight here rather than flicker through STALE first.
     */
    @Transactional
    public void markExpired(UUID id, Instant cutoff) {
        Trip trip = tripRepository.findByIdForUpdate(id).orElse(null);
        if (trip == null || !ACTIVE_STATUSES.contains(trip.getStatus())) {
            return;
        }
        if (isFresherThan(trip, cutoff)) {
            return;
        }
        trip.setStatus(TripStatus.EXPIRED);
        trip.setCompletedAt(Instant.now());
        freeVehicleIfOnTrip(trip);
        tripRepository.save(trip);
    }

    private boolean isFresherThan(Trip trip, Instant cutoff) {
        Instant lastActivity = trip.getLastPositionAt() != null ? trip.getLastPositionAt() : trip.getCreatedAt();
        return lastActivity.isAfter(cutoff);
    }

    @Transactional
    public Trip cancel(UUID id) {
        Trip trip = getForUpdate(id);
        if (!ACTIVE_STATUSES.contains(trip.getStatus())) {
            throw new ConflictException(
                    "Trip " + id + " is not active (current status: " + trip.getStatus() + "), cannot cancel");
        }

        trip.setStatus(TripStatus.CANCELLED);
        trip.setCompletedAt(Instant.now());
        freeVehicleIfOnTrip(trip);
        tripRepository.save(trip);

        return trip;
    }

    /**
     * Frees the vehicle back to AVAILABLE only if it's currently ON_TRIP.
     * Without this guard, every terminal path unconditionally overwrote
     * vehicle status — silently undoing an operator's manual MAINTENANCE or
     * RETIRED change made mid-trip.
     */
    private void freeVehicleIfOnTrip(Trip trip) {
        Vehicle vehicle = trip.getVehicle();
        if (vehicle.getStatus() == VehicleStatus.ON_TRIP) {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
            vehicleRepository.save(vehicle);
        }
    }
}
