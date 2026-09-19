package com.evas.device;

import java.util.List;
import java.util.UUID;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LamppostRepository extends JpaRepository<Lamppost, UUID> {

    List<Lamppost> findByActive(boolean active);

    /** Same shape as {@code TrafficSignalRepository#findNextAlongRoute} — see its Javadoc. */
    @Query(value = "SELECT * FROM lamppost l "
            + "WHERE l.active = true "
            + "AND ST_DWithin(CAST(l.location AS geography), CAST(:route AS geography), :corridorMeters) "
            + "AND ST_LineLocatePoint(:route, l.location) > ST_LineLocatePoint(:route, :position) "
            + "ORDER BY ST_LineLocatePoint(:route, l.location) ASC "
            + "LIMIT :limitCount",
            nativeQuery = true)
    List<Lamppost> findNextAlongRoute(
            @Param("route") LineString route,
            @Param("position") Point position,
            @Param("corridorMeters") double corridorMeters,
            @Param("limitCount") int limitCount);
}
