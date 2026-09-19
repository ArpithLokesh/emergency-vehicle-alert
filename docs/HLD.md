# High-Level Design

**Version:** 1.4.0
**Status:** MVP-0, MVP-1, MVP-2 implemented and verified end-to-end. MVP-3 (alerting/preemption) next.

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
- **External vehicle simulator** → a *separate* application (not part of
  this backend) that POSTs vehicle positions to the ingestion endpoint,
  exactly as a real GPS/AVL unit would. The backend is purely reactive to
  this feed — it runs no in-process simulator and no position scheduler.
  (Boundary contract: `docs/SIMULATOR-API-CONTRACT.md`.) The one scheduled
  job the backend does run is the stall watchdog — see below.
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
- **Trip** — owns trip lifecycle
  (`EN_ROUTE`/`ARRIVED`/`CANCELLED`/`STALE`/`EXPIRED`), the entry point
  dispatcher calls hit. ✅ Built, MVP-1 (creation + cancel); MVP-2 added
  position ingestion, arrival detection (`→ ARRIVED`), and a two-tier
  stall watchdog (`→ STALE` soft/recoverable, `→ EXPIRED` hard/terminal).
- **Routing** — talks to OSRM, stores route geometry, owns the Caffeine
  route-response cache. ✅ Built, MVP-1. The "devices near the vehicle"
  proximity query is **not** here — it's alerting-internal (MVP-3), see
  correction below.
- **Position** — position ingestion (`POST /api/trips/{id}/position`),
  arrival detection, and the stall watchdog. ✅ Built, MVP-2. Reactive
  only: the vehicle feed is external (see System Context); the watchdog is
  the sole scheduled job, present precisely to detect *absence* of the feed.
  No ETA computation here — that's alerting's.
- **Alerting** — reacts to position updates (asynchronously, see wiring
  decision), runs the device-proximity query internally, computes ETA,
  fires/records lamppost alerts (idempotent per trip+device), publishes to
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

Steps 1–4 and 8 are built and verified (MVP-1 + MVP-2). Steps 5–7 (alerting/
preemption) are the designed target for MVP-3, not yet implemented.

1. Dispatcher calls **Trip** to create a trip (vehicle + origin +
   destination). ✅
2. **Trip** asks **Routing** for a route. Routing checks the Caffeine cache
   (key: origin + destination only — no vehicle-profile dimension, see
   decisions below); on miss, calls OSRM, stores the route geometry,
   caches the response. Fail-fast: any OSRM failure aborts the whole
   request, nothing is persisted. ✅
3. Trip status is `EN_ROUTE`; the vehicle flips to `ON_TRIP`. ✅
4. The **external simulator** POSTs the vehicle's live positions to
   `POST /api/trips/{id}/position`. **Position** persists each as
   `last_known_position` (+ `last_position_at`) and returns a minimal
   receipt — no device data (the vehicle is a dumb emitter). ✅
5. *(MVP-3, not built)* A position update publishes a `PositionUpdatedEvent`;
   **Alerting** reacts **asynchronously** (see wiring decision), running the
   device-proximity query internally: `ST_DWithin` around the current
   position (GIST-indexed) → ahead-only filter via `ST_LineLocatePoint` →
   along-route ETA. This proximity query is alerting-internal; it is *not* a
   public endpoint and *not* run on the position write path.
6. *(MVP-3, not built)* **Alerting** checks ETA-to-lamppost against the
   (hardcoded, pre-MVP-5) threshold; once crossed, publishes a broadcast
   instruction to RabbitMQ + records it, won't re-fire for that
   trip+lamppost pair.
7. *(MVP-3, not built)* **Preemption** does the equivalent for signals:
   force-green once within lead time, revert once the vehicle passes (or a
   safety timeout fires).
8. **Trip** flips to `ARRIVED` when the vehicle's *current position* comes
   within `arrivalThresholdMeters` (~30m, configurable) of `destination` —
   a destination-proximity check (Java Haversine, point-to-point), **not** a
   traveled-distance-along-route check, which would silently break on any
   real-world detour. Frees the vehicle to `AVAILABLE`. ✅
9. **Stall watchdog, two-tier**: if no position arrives for `staleTimeout`
   (~120s, configurable), the trip flips to `STALE` — **paused, not
   terminal**: the vehicle stays `ON_TRIP`, and the next position update
   resumes the trip straight back to `EN_ROUTE`. Only if silence continues
   past `expireTimeout` (~300s) does the trip flip to `EXPIRED` (terminal)
   and the vehicle get freed. This tolerates a real GPS blackout (tunnel,
   underpass, dense-urban canyon) without stranding a live emergency
   response, while a genuinely dead feed still eventually frees the
   vehicle. The one scheduled job in an otherwise-reactive backend,
   present precisely to detect the *absence* of the feed. ✅

Dispatcher can also cancel a trip (`EN_ROUTE → CANCELLED`) at any point —
built, MVP-1.

## Key Architectural Decisions

- **Component wiring — async, not direct** (reversed from an earlier draft):
  when Alerting/Preemption arrive (MVP-3), a position update will publish a
  `PositionUpdatedEvent` after commit, and they react via
  `@Async @TransactionalEventListener(AFTER_COMMIT)` — decoupled and on a
  separate thread. A prior draft of this HLD called for Position to invoke
  `AlertingService.evaluate()` *directly* (chosen then for breakpoint-level
  traceability). That's reversed: position ingestion must get a fast ack and
  must never fail or roll back because a downstream alert publish was slow or
  errored — losing GPS data over an alerting hiccup is backwards. Alerting
  only ever sees committed positions. *(Principle locked; event + listeners
  built in MVP-3 — no event is published in MVP-2 since nothing listens.)*
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
- **Trip fields were deliberately trimmed** in MVP-1: `route_distance_meters`,
  `route_duration_seconds`, `traveled_meters`, `simulated_speed_mps` cut
  (none had a consumer). `last_known_position` was also cut then and **added
  back in MVP-2** (+ `last_position_at`) once position ingestion and stall
  detection actually needed them — the trim-until-needed discipline working
  as intended.
- **Stall detection is two-tier** (MVP-2, revised after MVP-2 shipped): a
  `@Scheduled` watchdog marks `EN_ROUTE` trips `STALE` after `staleTimeout`
  of silence, using `COALESCE(last_position_at, created_at)` so it catches
  both a feed that died mid-trip and one that never started. **`STALE` is
  deliberately not terminal** — real GPS feeds drop for real reasons
  (tunnels, underpasses, dense-urban canyons) that don't mean the run is
  over, so the vehicle stays `ON_TRIP` and the next position update
  resumes the trip transparently back to `EN_ROUTE`. Only after a second,
  longer `expireTimeout` of continued silence does the trip flip to
  `EXPIRED` (terminal, frees the vehicle) — reachable directly from
  `EN_ROUTE` too, so a watchdog sweep delayed long enough to cross both
  cutoffs at once goes straight to `EXPIRED` rather than flicker through
  `STALE` first. This is the *only* scheduled job in the backend —
  justified because detecting the *absence* of input is the one thing a
  purely reactive design cannot do on its own. `EXPIRED` is the terminal
  status distinct from operator `CANCELLED` (system-detected loss vs.
  operator action — matters for audit); `STALE` is a paused in-between
  state, not a terminal one. The original single-tier design (`STALE`
  terminal at 45s) shipped in MVP-2 and was revised immediately after
  real-world review. ✅
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
| 1.3.0 | 2026-09-19 | MVP-2 implemented + several decisions reshaped. Vehicle simulator is **external** (backend reactive, no in-process sim). Position component built: ingestion endpoint, destination-proximity arrival, and a stall watchdog (`STALE` status). Component wiring **reversed** to async post-commit events (from the earlier direct-call plan). Device-proximity/ETA is **alerting-internal (MVP-3)**, not a public endpoint — no `nearby-devices` API. Trip regained `last_known_position` + `last_position_at`. |
| 1.4.0 | 2026-09-20 | **Stall detection made two-tier.** `STALE` (45s single-tier, terminal) revised almost immediately after real-world review: a real GPS blackout (tunnel, underpass, urban canyon) shouldn't strand a live emergency response. `STALE` is now a **paused, non-terminal** state (~120s, vehicle stays `ON_TRIP`, next position resumes transparently to `EN_ROUTE`); a new `EXPIRED` status (~300s) is the actual terminal outcome for a feed that never comes back. `cancel()` now accepts `STALE` trips too. Simulator contract (C3/C4) and MVP-2 LLD updated to match. |
