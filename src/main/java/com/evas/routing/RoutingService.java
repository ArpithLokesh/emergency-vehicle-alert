package com.evas.routing;

import com.evas.common.GeoPoints;
import org.locationtech.jts.geom.LineString;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls OSRM for a road-snapped route between two points. Used from
 * TripService at trip-creation time; any failure here means the trip isn't
 * created at all (fail-fast — see docs/mvp-1/LLD-trip-and-routing.md).
 *
 * <p>Everything environment-specific — which OSRM server, which routing
 * profile, how long to wait before giving up — comes from
 * {@link RoutingProperties} (application.yml), not this class. Pointing at
 * a self-hosted OSRM instance, or one serving a different profile, is a
 * config change, never a code change.
 */
@Service
public class RoutingService {

    private final RestClient restClient;
    private final String profile;

    public RoutingService(RoutingProperties properties) {
        this.profile = properties.profile();

        // Plain core-Spring SimpleClientHttpRequestFactory, not one of
        // Boot's higher-level client-factory helpers — after the Flyway
        // module-split surprise, this avoids depending on a Boot class
        // whose package/API might have moved in this version.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.osrmBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Cached on (originLat, originLng, destLat, destLng) — Spring's default
     * key generator combines whatever parameters the method takes, so
     * plain doubles give a simple, reliable cache key with no custom SpEL
     * needed. See {@code CacheConfig} for the Caffeine-backed CacheManager
     * this relies on.
     */
    @Cacheable(CacheConfig.OSRM_ROUTES_CACHE)
    public LineString getRoute(double originLat, double originLng, double destLat, double destLng) {
        String coordinates = "%s,%s;%s,%s".formatted(originLng, originLat, destLng, destLat);

        OsrmRouteResponse response;
        try {
            response = restClient.get()
                    .uri("/route/v1/{profile}/{coordinates}?overview=full&geometries=geojson", profile, coordinates)
                    .retrieve()
                    .body(OsrmRouteResponse.class);
        } catch (RestClientException e) {
            throw new RoutingException("Failed to reach OSRM at " + coordinates, e);
        }

        if (response == null || response.routes() == null || response.routes().isEmpty()) {
            throw new RoutingException("OSRM returned no route for " + coordinates);
        }
        if (!"Ok".equals(response.code())) {
            throw new RoutingException("OSRM returned code " + response.code() + " for " + coordinates);
        }

        OsrmRoute route = response.routes().get(0);
        if (route.geometry() == null
                || route.geometry().coordinates() == null
                || route.geometry().coordinates().size() < 2) {
            throw new RoutingException("OSRM returned no usable geometry for " + coordinates);
        }

        return GeoPoints.lineStringFromLngLatPairs(route.geometry().coordinates());
    }
}
