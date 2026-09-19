package com.evas.alerting;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Per-trip alerting state, in-memory only — a raw Caffeine map rather than
 * Spring's {@code @Cacheable} abstraction, since this is an explicitly
 * managed key-value store (manual get/put/invalidate driven by trip
 * lifecycle events) rather than memoizing a pure function's return value
 * the way {@code CacheConfig}'s OSRM route cache does.
 *
 * <p>No size bound or TTL: one entry per currently-active (EN_ROUTE or
 * STALE) trip, which is already bounded by how many emergency runs can be
 * live at once — not a value that grows with request volume the way the
 * OSRM route cache does. Entries are removed explicitly on trip
 * termination, not left to expire.
 *
 * <p>Never the source of truth for anything — a missing or stale entry is
 * always safe to rebuild from the database (see
 * {@code AlertingCacheWarmup} for the backend-restart case).
 */
@Component
public class TripAlertingCache {

    private final Cache<UUID, TripAlertingState> cache = Caffeine.newBuilder().build();

    public Optional<TripAlertingState> get(UUID tripId) {
        return Optional.ofNullable(cache.getIfPresent(tripId));
    }

    public void put(UUID tripId, TripAlertingState state) {
        cache.put(tripId, state);
    }

    public void invalidate(UUID tripId) {
        cache.invalidate(tripId);
    }

    public long size() {
        return cache.estimatedSize();
    }
}
