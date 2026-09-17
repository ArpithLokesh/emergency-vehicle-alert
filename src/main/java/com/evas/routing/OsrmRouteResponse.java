package com.evas.routing;

import java.util.List;

/**
 * The slice of OSRM's {@code /route/v1/...} response we actually use.
 * Package-private — this is RoutingService's internal wire format, not
 * something the rest of the app should depend on. Jackson deserializes
 * records natively, no extra config needed.
 */
record OsrmRouteResponse(String code, List<OsrmRoute> routes) {
}

record OsrmRoute(OsrmGeometry geometry) {
}

/** {@code coordinates} is a list of [lng, lat] pairs, per the GeoJSON spec OSRM follows. */
record OsrmGeometry(List<List<Double>> coordinates) {
}
