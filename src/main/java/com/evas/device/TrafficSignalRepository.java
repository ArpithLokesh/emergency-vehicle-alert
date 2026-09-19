package com.evas.device;

import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrafficSignalRepository extends JpaRepository<TrafficSignal, UUID> {

    List<TrafficSignal> findByActive(boolean active);

    /**
     * The next {@code limitCount} active signals ahead of {@code position}
     * along {@code route}, within {@code corridorMeters} of the route
     * centerline. Backs the alerting engine's device window (see
     * {@code com.evas.alerting.TripWindowBuilder}) — used both to build a
     * fresh window (large {@code limitCount}) and to refill one slot after
     * a device is popped (limitCount=1, position = the tail's location).
     *
     * <p>{@code ST_LineLocatePoint} returns a 0..1 fraction along the line
     * regardless of the line's CRS, so ordering/filtering by it works
     * correctly for a lat/lng (SRID 4326) route despite the coordinates not
     * being in real-world distance units. The proximity filter
     * ({@code ST_DWithin} on {@code ::geography}) is what actually needs
     * real-world metres, which is why that part alone casts to geography.
     */
    @Query(value = "SELECT * FROM traffic_signal s "
            + "WHERE s.active = true "
            + "AND ST_DWithin(CAST(s.location AS geography), CAST(:route AS geography), :corridorMeters) "
            + "AND ST_LineLocatePoint(:route, s.location) > ST_LineLocatePoint(:route, :position) "
            + "ORDER BY ST_LineLocatePoint(:route, s.location) ASC "
            + "LIMIT :limitCount",
            nativeQuery = true)
    List<TrafficSignal> findNextAlongRoute(
            @Param("route") LineString route,
            @Param("position") Point position,
            @Param("corridorMeters") double corridorMeters,
            @Param("limitCount") int limitCount);
}
