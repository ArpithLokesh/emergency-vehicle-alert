package com.evas.routing;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manually wires the CacheManager rather than relying on Spring Boot's
 * {@code spring.cache.type} autoconfiguration — {@code @EnableCaching} and
 * {@code CacheManager} are core Spring Framework (spring-context), not a
 * Boot integration, so this sidesteps the same "raw library on the
 * classpath isn't enough" trap that bit Flyway on this Spring Boot version.
 *
 * <p>Cache sizing/TTL comes from {@link OsrmCacheProperties}
 * (application.yml) — tuning the cache for a different environment is a
 * config change, not a code change.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Shared with RoutingService's @Cacheable so the name exists in exactly one place. */
    public static final String OSRM_ROUTES_CACHE = "osrmRoutes";

    @Bean
    public CacheManager cacheManager(OsrmCacheProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(OSRM_ROUTES_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .expireAfterWrite(properties.expireAfterWrite()));
        return manager;
    }
}
