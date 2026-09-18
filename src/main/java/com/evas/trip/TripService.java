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
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
        if (tripRepository.existsByVehicleIdAndStatus(vehicleId, TripStatus.EN_ROUTE)) {
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
     */
    @Transactional
    public Trip updatePosition(UUID id, GeoPointDto positionDto) {
        Trip trip = get(id);
        if (trip.getStatus() != TripStatus.EN_ROUTE) {
            throw new ConflictException(
                    "Trip " + id + " is not EN_ROUTE (current status: " + trip.getStatus()
                            + "), cannot accept position updates");
        }

        Point position = GeoPoints.fromDto(positionDto);
        trip.setLastKnownPosition(position);
        trip.setLastPositionAt(Instant.now());

        if (GeoPoints.distanceMeters(position, trip.getDestination())
                <= trackingProperties.arrivalThresholdMeters()) {
            trip.setStatus(TripStatus.ARRIVED);
            trip.setCompletedAt(Instant.now());
            Vehicle vehicle = trip.getVehicle();
            vehicle.setStatus(VehicleStatus.AVAILABLE);
            vehicleRepository.save(vehicle);
        }

        return tripRepository.save(trip);
    }

    public Trip get(UUID id) {
        return tripRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Trip " + id + " not found"));
    }

    public List<Trip> list(TripStatus status) {
        return status != null ? tripRepository.findByStatus(status) : tripRepository.findAll();
    }

    /**
     * Marks a stalled trip STALE and frees its vehicle. Called per-trip by
     * the watchdog (a separate bean, so this @Transactional actually applies
     * — a self-call would bypass the proxy). Re-checks EN_ROUTE inside the
     * transaction: the trip may have ARRIVED or been CANCELLED between the
     * watchdog's scan and this call, in which case we leave it alone.
     */
    @Transactional
    public void markStale(UUID id) {
        Trip trip = tripRepository.findById(id).orElse(null);
        if (trip == null || trip.getStatus() != TripStatus.EN_ROUTE) {
            return;
        }
        trip.setStatus(TripStatus.STALE);
        trip.setCompletedAt(Instant.now());
        tripRepository.save(trip);

        Vehicle vehicle = trip.getVehicle();
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        vehicleRepository.save(vehicle);
    }

    @Transactional
    public Trip cancel(UUID id) {
        Trip trip = get(id);
        if (trip.getStatus() != TripStatus.EN_ROUTE) {
            throw new ConflictException(
                    "Trip " + id + " is not EN_ROUTE (current status: " + trip.getStatus() + "), cannot cancel");
        }

        trip.setStatus(TripStatus.CANCELLED);
        trip.setCompletedAt(Instant.now());
        tripRepository.save(trip);

        Vehicle vehicle = trip.getVehicle();
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        vehicleRepository.save(vehicle);

        return trip;
    }
}
