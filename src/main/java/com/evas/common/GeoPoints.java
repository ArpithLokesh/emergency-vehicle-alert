package com.evas.common;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
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
}
