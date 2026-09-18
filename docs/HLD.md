# High-Level Design

**Version:** 1.2.0
**Status:** MVP-0 and MVP-1 implemented and verified end-to-end. MVP-2 next.

This is the single, living end-to-end HLD for the Emergency Vehicle Alert
System backend — the whole-system architecture, updated in place as design
evolves, with a changelog below tracking what changed and why. It replaces
an earlier attempt to split this into a delta file per MVP stage
(`docs/mvp-0/HLD.md`, `docs/mvp-1/HLD.md`) — that fragmentation made it
hard to see the current end-to-end design without reading multiple files
and mentally merging them, which defeats the point of an HLD. **LLDs stay
organized per MVP stage** (`docs/mvp-N/LLD-*.md`) — that split works fine,
since each LLD is genuinely scoped to what that stage builds. Only the HLD
is consolidated back into one document.

For product-level context (what this system is, locked product decisions,
the MVP sequence itself), see `/ROADMAP.md` — not duplicated here.

## System Context

Who/what talks to this backend:

- **Dispatcher** (human, via console/UI) → creates/cancels trips.
- **Admin** (human, via console/UI) → manages vehicles, devices, and (from
  MVP-5) policy.
- **OSRM** (external HTTP service) → the backend calls it for road-snapped
  routes.
- **Postgres/PostGIS** → the backend's own datastore.
- **Position simulator** → runs *inside* the backend process for now (not
  yet built — MVP-2), behind an ingestion interface designed so a real GPS
  feed can implement the same interface later without touching downstream
  logic.
- **RabbitMQ** → the backend publishes lamppost alert instructions here;
  a real lamppost device (out of scope) would consume from its bound queue.
  No real device exists in this project — the API/messaging surface is
  designed as if one could. (Locked as the transport; not yet wired up —
  MVP-3.)

## Internal Components

Maps 1:1 to the package-by-feature layout (`com.evas.<feature>`, see
ROADMAP.md). Status noted per component — this is the fastest way to see
what's actually built vs. still design-only.

- **Vehicle** — vehicle registry (CRUD, status lifecycle). ✅ Built, MVP-0.
- **Device** — Lamppost + TrafficSignal registries (CRUD, active/inactive).
  ✅ Built, MVP-0. Spatial "devices along a route" query not yet built —
  see the happy-path correction below.
- **Trip** — owns trip lifecycle (`EN_ROUTE`/`ARRIVED`/`CANCELLED`), the
  entry point dispatcher calls hit. ✅ Built, MVP-1 (creation + cancel;
  `ARRIVED` is unreachable until Position exists).
- **Routing** — talks to OSRM, stores route geometry, owns the Caffeine
  route-response cache. ✅ Built, MVP-1. The "devices along this route"
  spatial query is **not** part of this component's current scope — see
  correction below.
- **Position** — the ingestion interface + the in-process simulator
  implementation; advances a trip's position on a timer and recomputes
  ETA-to-each-watched-device. ❌ Not built — MVP-2.
- **Alerting** — watches ETA-to-lamppost, fires/records alert instructions
  once threshold is crossed (idempotent per trip+device), publishes to
  RabbitMQ. ❌ Not built — MVP-3.
- **Preemption** — watches ETA-to-signal, fires force-green / reverts,
  records override events. ❌ Not built — MVP-3.
- **Audit** — read/query side for the event history Alerting/Preemption
  produce (not a separate write path — those components persist their own
  records; Audit is mainly about exposing query endpoints over that data
  later). ❌ Not built — post-MVP-3.
- *(Policy and Auth components exist in the package layout but stay out of
  the data flow below until MVP-5/MVP-4 respectively — before that,
  thresholds are constants and endpoints are open.)*

## Core "Happy Path" Data Flow

Steps 1–3 are built and verified (MVP-1). Steps 4 onward are the designed
target for MVP-2/MVP-3, not yet implemented.

1. Dispatcher calls **Trip** to create a trip (vehicle + origin +
   destination). ✅
2. **Trip** asks **Routing** for a route. Routing checks the Caffeine cache
   (key: origin + destination only — no vehicle-profile dimension, see
   decisions below); on miss, calls OSRM, stores the route geometry,
   caches the response. Fail-fast: any OSRM failure aborts the whole
   request, nothing is persisted. ✅
3. Trip status is `EN_ROUTE`; the vehicle flips to `ON_TRIP`. ✅
4. *(MVP-2, not built)* **Position** starts advancing a virtual vehicle
   along the stored route geometry on a timer (every 1–5s), recomputing
   ETA to each upstream device.
5. *(MVP-3, not built)* **Routing** runs the device-along-route spatial
   query against **Device** at this point — **not** at trip-creation time
   as originally designed. Corrected during MVP-1: nothing consumes that
   "devices to watch" list until Alerting exists, so building it earlier
   would be dead code sitting unused for two stages.
6. *(MVP-3, not built)* **Alerting** checks ETA-to-lamppost against the
   (hardcoded, pre-MVP-5) threshold; once crossed, fires an alert
   instruction (published to RabbitMQ) + records it, won't re-fire for
   that trip+lamppost pair.
7. *(MVP-3, not built)* **Preemption** does the equivalent for signals:
   force-green once within lead time, revert once the vehicle's position
   passes the signal's point on the route (or a safety timeout fires).
8. *(MVP-2, not built)* **Trip** flips to `ARRIVED` when the vehicle's
   *current position* comes within a threshold distance (e.g. ~20–50m, TBD
   when built) of the trip's `destination` point — **not** via a
   traveled-distance-along-planned-route check, which silently breaks the
   moment a real vehicle takes a detour (obstruction, driver choice,
   traffic), since it assumes the vehicle stays exactly on the
   OSRM-planned polyline. A destination-proximity check (simple
   point-to-point distance, independent of the route geometry) is
   deviation-tolerant and doesn't require full mid-trip re-routing support
   (still a stretch goal) to be correct. This means `Trip` will need a
   `last_known_position` field when MVP-2's LLD is designed — deliberately
   trimmed from the MVP-1 schema since nothing read it yet (see decisions
   below), but it comes back here.

Dispatcher can also cancel a trip (`EN_ROUTE → CANCELLED`) at any point —
built, MVP-1.

## Key Architectural Decisions

- **Component wiring**: Position's tick handler will call **directly**
  into `AlertingService.evaluate(trip)` and `PreemptionService.evaluate(trip)`
  after updating ETA — no internal event bus. Chosen over Spring
  application events because a direct call chain is easier to trace/debug
  with breakpoints while still learning how the pieces connect; the
  coupling cost is small at this scale. Revisit if a third reactor needs
  to hook into position updates and the direct-call list gets unwieldy.
  *(Designed, not yet exercised — MVP-2/3.)*
- **Trip creation is fail-fast**: OSRM is called synchronously inside
  `POST /api/trips`. On failure, nothing is persisted — no `Trip` row, no
  vehicle status change — dispatcher just retries. No `ROUTING_FAILED`
  status, no nullable route columns. Verified: an unreachable OSRM host
  produces a 502 with the vehicle still `AVAILABLE` and zero trips
  created. ✅
- **One active trip per vehicle**: enforced at both the application layer
  (clean 409 with a real message) and the database layer (partial unique
  index on `vehicle_id` where `status = 'EN_ROUTE'`) — the same
  belt-and-suspenders pattern used for `Vehicle.call_sign`,
  `Lamppost.code`/`routing_key`. ✅
- **`TripStatus` includes all three values** (`EN_ROUTE`/`ARRIVED`/
  `CANCELLED`) even though `ARRIVED` is unreachable until MVP-2 — avoids a
  second migration touching the same CHECK constraint later. The one place
  this design deliberately did *not* trim to only what's immediately used.
- **Trip fields were deliberately trimmed**: `route_distance_meters`,
  `route_duration_seconds`, `traveled_meters`, `simulated_speed_mps` were
  cut from the original draft (none had a consumer). `last_known_position`
  was also cut but is now known to be needed back for MVP-2 (see happy-path
  step 8) — add it then, not speculatively now.
- **Caching**: Spring's cache abstraction (`@Cacheable`) backed by
  Caffeine (in-memory), fitting the single-instance/no-HA deployment
  model. Swappable to Redis later by changing only the `CacheManager`
  bean. Cache key is `(origin, destination)` only — no vehicle-profile
  dimension; all current vehicle types route identically, so that
  dimension was speculative scope. `CacheManager` is wired **manually**
  (`CacheConfig`), not via Spring Boot's `spring.cache.type`
  autoconfiguration — `CaffeineCacheManager` lives in a *different*
  artifact (`spring-context-support`) than core `spring-context`, a
  genuine module boundary (same *kind* of lesson the Flyway gotcha below
  taught, though unrelated to it). ✅
- **All routing/caching tuning is externalized** via
  `@ConfigurationProperties` (`RoutingProperties`, `OsrmCacheProperties`,
  bound through `@ConfigurationPropertiesScan`) — OSRM base URL, routing
  profile, connect/read timeouts, cache size/TTL all live in
  `application.yml`. Swapping OSRM servers or retuning the cache is a
  config change, never a code change. ✅
- **`RestClient`'s request factory is plain core Spring**
  (`SimpleClientHttpRequestFactory`), not one of Boot's higher-level
  client-factory helper classes — after two module-boundary surprises,
  prefer the class whose package has been stable the longest. ✅
- **Alert transport**: RabbitMQ. A topic exchange (e.g. `lamppost.alerts`),
  each `Lamppost.routingKey` binds its own queue to it. Full
  exchange/queue/binding topology belongs with the Alerting engine
  (MVP-3) — `Lamppost.routingKey` exists on the entity already, unused
  until then.
- **Schema management**: Flyway, versioned migrations — not Hibernate
  `ddl-auto` (`ddl-auto: validate` confirms entities match what Flyway
  created). On this Spring Boot version, needs `spring-boot-starter-flyway`
  — raw `flyway-core` alone silently doesn't wire up (Spring Boot 4 split
  autoconfiguration into per-technology modules; no error, no log line,
  IntelliJ can't resolve `spring.flyway.*` properties either). ✅

## Changelog

| Version | Date | Change |
|---|---|---|
| 1.0.0 | 2026-09-14 | Initial HLD: system context, components, happy-path data flow, wiring/caching/messaging/schema decisions. |
| 1.1.0 | 2026-09-14 | MVP-1 implemented: Trip + Routing + Caching. Corrected happy-path step 3 (device-along-route query deferred to MVP-3). Written as a separate delta file at the time (`docs/mvp-1/HLD.md`). |
| 1.2.0 | 2026-09-17 | **Consolidated back into a single document.** The mvp-0/mvp-1 HLD-delta-file split made the current end-to-end design hard to see without reading and merging multiple files — reverted. `docs/mvp-0/HLD.md` and `docs/mvp-1/HLD.md` are retired; this file is the sole HLD going forward. Also folded in the config-externalization decision (`RoutingProperties`/`OsrmCacheProperties`) that had been implemented after `docs/mvp-1/HLD.md` was last written and had no HLD home. LLDs remain per-MVP-stage (`docs/mvp-N/LLD-*.md`) — unaffected by this change. |
