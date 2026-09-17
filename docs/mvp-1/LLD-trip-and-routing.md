# LLD: Trip + Routing (MVP-1)

**HLD version this was written against:** 1.1.0 (`docs/mvp-1/HLD.md`)
**Status:** Implemented and verified end-to-end (curl, real OSRM calls,
cache-hit timing, failure-path testing). See ROADMAP.md for the full
verification log.

## Trip

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | primary key, generated |
| `vehicle` | FK → Vehicle, NOT NULL | |
| `origin` / `destination` | Point (SRID 4326), NOT NULL | |
| `route_geometry` | LineString (SRID 4326), NOT NULL | from OSRM; never null since a Trip only exists once routing succeeded (fail-fast) |
| `status` | enum: `EN_ROUTE`, `ARRIVED`, `CANCELLED` | NOT NULL |
| `created_at` | Instant, NOT NULL | |
| `completed_at` | Instant, nullable | set on `ARRIVED` or `CANCELLED` |

Deliberately **not** included (trimmed from an earlier draft — see
`docs/mvp-1/HLD.md`): `route_distance_meters`, `route_duration_seconds`,
`traveled_meters`, `simulated_speed_mps`, `last_known_position`. None have
a consumer yet; add back when the MVP-2 simulator actually needs them.

**DB constraint**: `idx_trip_vehicle_active` — a partial unique index on
`vehicle_id` `WHERE status = 'EN_ROUTE'`, enforcing "one active trip per
vehicle" as a race-safety net alongside the app-level check in
`TripService`.

## TripService — where the business logic actually lives

The first feature where a service layer earns its place (Vehicle/Lamppost/
TrafficSignal are pure CRUD, no service needed).

- `create(vehicleId, origin, destination)`: validates the vehicle is
  `AVAILABLE`, validates no existing `EN_ROUTE` trip for it, calls
  `RoutingService` for the route, persists the `Trip`, flips the vehicle to
  `ON_TRIP`. `@Transactional` — the trip-save and vehicle-save must commit
  together or not at all.
- `cancel(id)`: validates the trip is `EN_ROUTE`, sets `CANCELLED` +
  `completed_at`, flips the vehicle back to `AVAILABLE`. Also
  `@Transactional`.
- `get`/`list` are plain reads, no transaction needed.

## RoutingService (`com.evas.routing`)

Calls OSRM's `/route/v1/{profile}/{lng1},{lat1};{lng2},{lat2}?overview=full&geometries=geojson`
via Spring's `RestClient`. Parses the GeoJSON coordinate array into a JTS
`LineString` via `GeoPoints.lineStringFromLngLatPairs`.

- **Configuration is fully externalized** (`RoutingProperties`, bound from
  `routing.*` in `application.yml` via `@ConfigurationProperties` +
  `@ConfigurationPropertiesScan`): `osrm-base-url`, `profile` (default
  `driving`), `connect-timeout`/`read-timeout` (default `5s` each, Spring
  Boot parses simple duration strings natively). Pointing at a self-hosted
  OSRM instance, or one serving a different profile, is a config change,
  never a code change.
- **Fail-fast**: any non-2xx response or timeout throws `RoutingException`
  (mapped to 502 by `GlobalExceptionHandler`). `TripService.create` never
  catches it — it propagates straight out, so nothing gets persisted.
- **Caching**: `@Cacheable(CacheConfig.OSRM_ROUTES_CACHE)` on
  `getRoute(originLat, originLng, destLat, destLng)` — plain `double`
  parameters so Spring's default key generator builds the cache key with
  no custom SpEL needed. A thrown exception is never cached (only
  successful returns are) — confirmed when a transient OSRM network
  failure correctly did *not* poison the cache for that coordinate pair.
  Verified: a repeat call for the same coordinates returned in ~17ms vs
  ~3s for a fresh pair.
- **`CacheConfig`**: manually configures a `CaffeineCacheManager`, sized
  from `OsrmCacheProperties` (`caching.osrm-routes.*` — `maximum-size`,
  `expire-after-write`, defaults `500`/`24h`) rather than hardcoded
  literals, and rather than relying on Spring Boot's cache
  autoconfiguration — see `docs/mvp-1/HLD.md` for why the latter.

## New exception types (`com.evas.common`)

- `ConflictException` → 409, for business-rule conflicts ("vehicle not
  AVAILABLE", "vehicle already has an active trip", "trip not cancellable")
  — distinct from `DataIntegrityViolationException` (DB unique-constraint
  violations), which is a different kind of 409.
- `RoutingException` (`com.evas.routing`) → 502, for OSRM failures.

## Trip REST Contract

- `POST /api/trips` — body `{vehicleId, origin: {lat,lng}, destination:
  {lat,lng}}` → 201 with the created trip (route as a list of `{lat,lng}`
  points, matching the project's plain-JSON geometry convention).
- `GET /api/trips` — filterable by `status`.
- `GET /api/trips/{id}`.
- `POST /api/trips/{id}/cancel` — a dedicated action endpoint rather than a
  generic `PATCH`, since cancelling is a state-transition with a side
  effect (frees the vehicle), not a field edit.

## Migration

`V2__create_trip_table.sql` — the `trip` table, an index on `vehicle_id`,
and the partial unique index for the active-trip constraint.

## Changelog

| Date | Change | Reason |
|---|---|---|
| 2026-09-14 | Implemented Trip, TripService, TripController, RoutingService, CacheConfig. Verified end-to-end: real OSRM routing, one-active-trip rejection, cancel flow, cache-hit timing, OSRM-failure fail-fast behavior. | Completes MVP-1 per ROADMAP.md. |
| 2026-09-17 | Externalized all hardcoded routing/caching config: added `RoutingProperties` (`osrm-base-url`, `profile`, `connect-timeout`, `read-timeout`) and `OsrmCacheProperties` (`maximum-size`, `expire-after-write`), bound via `@ConfigurationProperties` + `@ConfigurationPropertiesScan`. Removed the hardcoded `/driving/` profile, `5_000`ms timeouts, and `500`/`24h` cache literals. Re-verified end-to-end (fresh call + cache hit) after the change. | Swapping OSRM servers or tuning cache size/TTL should never require a code change. |
