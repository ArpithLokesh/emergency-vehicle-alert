package com.evas.trip;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface    TripRepository extends JpaRepository<Trip, UUID> {

    List<Trip> findByStatus(TripStatus status);

    /**
     * Whether the vehicle has a trip in any of the given statuses — used at
     * creation time to check for an active trip. STALE counts as active: a
     * paused trip still owns the vehicle, it just isn't currently EN_ROUTE.
     *
     * <p>"VehicleId" resolves to the nested vehicle.id property automatically
     * — Trip has no field literally named vehicleId, but it does have a
     * `vehicle` association, so Spring Data's query-derivation walks into it.
     */
    boolean existsByVehicleIdAndStatusIn(UUID vehicleId, Collection<TripStatus> statuses);

    /**
     * EN_ROUTE trips whose feed has gone silent past the soft cutoff — due
     * to be paused (STALE). COALESCE falls back to createdAt so a trip that
     * never sent a first position is caught too — can't be expressed as a
     * derived method name, hence JPQL.
     */
    @Query("SELECT t FROM Trip t WHERE t.status = com.evas.trip.TripStatus.EN_ROUTE "
            + "AND COALESCE(t.lastPositionAt, t.createdAt) < :cutoff")
    List<Trip> findOverdueForStale(@Param("cutoff") Instant cutoff);

    /**
     * EN_ROUTE or STALE trips whose feed has gone silent past the hard
     * cutoff — due to be terminated (EXPIRED). Deliberately includes
     * EN_ROUTE too: if a sweep is ever delayed long enough for a trip to
     * cross both cutoffs before being checked, it should go straight to
     * EXPIRED rather than flicker through STALE first.
     */
    @Query("SELECT t FROM Trip t WHERE t.status IN "
            + "(com.evas.trip.TripStatus.EN_ROUTE, com.evas.trip.TripStatus.STALE) "
            + "AND COALESCE(t.lastPositionAt, t.createdAt) < :cutoff")
    List<Trip> findOverdueForExpiry(@Param("cutoff") Instant cutoff);

    /**
     * Locking read for check-then-write paths on a Trip row (updatePosition,
     * markStale, cancel). PESSIMISTIC_WRITE translates to
     * {@code SELECT ... FOR UPDATE} in Postgres — any concurrent transaction
     * trying to acquire the same lock (via this method) or write the row
     * blocks until this transaction commits or rolls back.
     *
     * <p>The reason it exists: without it, two check-then-write callers on
     * the same trip (say the stall watchdog and a live position POST) can
     * both read {@code status == EN_ROUTE}, both compute their write, and
     * clobber each other on save() — leaving the trip and its vehicle in
     * inconsistent states. Serializing via the row lock removes the race.
     *
     * <p>Only used for writes — plain reads keep going through {@code findById}
     * so we don't add contention for no gain.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") UUID id);

    // The three queries below are pure geometry math with no FROM clause —
    // they don't touch the trip table at all, just take a route/point as
    // parameters. They live here rather than in a spatial utility class
    // because JTS has no geodesic (real-world-metre) equivalent of any of
    // them; PostGIS's geography-aware functions are the only correct source
    // for "how far in actual metres", the same reason GeoPoints.distanceMeters
    // hand-rolls Haversine instead of using JTS's own (planar) distance().
    // Used by the alerting engine (com.evas.alerting) to reason about a
    // vehicle's and a device's position along a trip's route.

    /** 0..1 fraction of how far along {@code route} the closest point to {@code point} is. */
    @Query(value = "SELECT ST_LineLocatePoint(CAST(:route AS geometry), CAST(:point AS geometry))", nativeQuery = true)
    double fractionAlongRoute(@Param("route") LineString route, @Param("point") Point point);

    /** Real-world length of {@code route} in metres. */
    @Query(value = "SELECT ST_Length(CAST(:route AS geography))", nativeQuery = true)
    double routeLengthMeters(@Param("route") LineString route);

    /** Real-world distance in metres from {@code point} to the nearest point on {@code route}. */
    @Query(value = "SELECT ST_Distance(CAST(:route AS geography), CAST(:point AS geography))", nativeQuery = true)
    double distanceToRouteMeters(@Param("route") LineString route, @Param("point") Point point);
}
