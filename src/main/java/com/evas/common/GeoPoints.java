package com.evas.common;

import java.util.Arrays;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Every geometry in this project uses SRID 4326 (WGS84 lat/lng) — the
 * coordinate system GPS, OSRM, and PostGIS all speak by default. Build
 * every JTS geometry through this factory so SRID stays consistent.
 *
 * <p><b>Gotcha:</b> JTS/PostGIS coordinate order is {@code (x, y)} =
 * {@code (longitude, latitude)} — the reverse of how people usually say
 * "lat, lng". {@link #of} takes lat/lng in the human order and flips it.
 */
public final class GeoPoints {

    public static final int SRID = 4326;

    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), SRID);

    private GeoPoints() {
    }

    public static Point of(double latitude, double longitude) {
        return FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    public static Point fromDto(GeoPointDto dto) {
        return of(dto.lat(), dto.lng());
    }

    public static GeoPointDto toDto(Point point) {
        return new GeoPointDto(point.getY(), point.getX());
    }

    /** Builds a route polyline from (lng, lat) pairs, e.g. straight from an OSRM GeoJSON response. */
    public static LineString lineStringFromLngLatPairs(List<List<Double>> lngLatPairs) {
        Coordinate[] coordinates = lngLatPairs.stream()
                .map(pair -> new Coordinate(pair.get(0), pair.get(1)))
                .toArray(Coordinate[]::new);
        return FACTORY.createLineString(coordinates);
    }

    public static List<GeoPointDto> toDtoList(LineString lineString) {
        return Arrays.stream(lineString.getCoordinates())
                .map(c -> new GeoPointDto(c.y, c.x))
                .toList();
    }

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * Great-circle (Haversine) distance in metres between two WGS84 points.
     * JTS's own {@code distance()} is planar (degrees), meaningless for
     * real-world metres, so this is computed by hand. Accurate to well within
     * the ~30 m arrival threshold at city scale.
     */
    public static double distanceMeters(Point a, Point b) {
        double lat1 = Math.toRadians(a.getY());
        double lat2 = Math.toRadians(b.getY());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.getX() - a.getX());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
