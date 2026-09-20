# High-Level Design

**Version:** 1.5.0
**Status:** MVP-0 through MVP-3 implemented and verified end-to-end. MVP-4 (auth) next.

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
- **RabbitMQ** → the backend publishes device commands to one topic
  exchange (`evas.device.commands`); a real traffic signal or lamppost
  (out of scope) would bind its own queue using its `routing_key` column
  and consume from there. No real device exists in this project — the
  API/messaging surface is designed as if one could. ✅ Wired, MVP-3.

## Internal Components

Maps 1:1 to the package-by-feature layout (`com.evas.<feature>`, see
ROADMAP.md). Status noted per component — this is the fastest way to see
what's actually built vs. still design-only.

- **Vehicle** — vehicle registry (CRUD, status lifecycle). ✅ Built, MVP-0.
- **Device** — Lamppost + TrafficSignal registries (CRUD, active/inactive).
  ✅ Built, MVP-0. `TrafficSignal` gained a `routingKey` column in MVP-3
  (Lamppost always had one) — both device types are now addressable on
  RabbitMQ the same way. The "next N devices ahead along a route" spatial
  query lives on each device's own repository (`findNextAlongRoute`),
  used by Alerting — see below.
- **Trip** — owns trip lifecycle
  (`EN_ROUTE`/`ARRIVED`/`CANCELLED`/`STALE`/`EXPIRED`), the entry point
  dispatcher calls hit. ✅ Built, MVP-1 (creation + cancel); MVP-2 added
  position ingestion, arrival detection (`→ ARRIVED`), and a two-tier
  stall watchdog (`→ STALE` soft/recoverable, `→ EXPIRED` hard/terminal).
- **Routing** — talks to OSRM, stores route geometry, owns the Caffeine
  route-response cache. ✅ Built, MVP-1. Also called mid-trip now (MVP-3)
  when Alerting detects the vehicle has deviated from its stored route.
- **Position** — position ingestion (`POST /api/trips/{id}/position`),
  arrival detection, and the stall watchdog. ✅ Built, MVP-2. Reactive
  only: the vehicle feed is external (see System Context); the watchdog is
  one of two scheduled jobs (the other being Alerting's safety-timeout
  sweep, below), present precisely to detect *absence* of the feed.
- **Alerting** — reacts to every position update (asynchronously, see
  wiring decision) by maintaining a rolling per-trip window of the next
  few devices ahead on the route (separate window per device type), and
  drives both device command streams from it: signal preemption
  (edge-triggered force-green/revert) and lamppost broadcast
  (level-triggered approach/clear). Also owns route-deviation detection
  (re-routes via **Routing** and rebuilds the windows) and a
  safety-timeout sweep that reverts any signal preemption stuck `ACTIVE`
  regardless of cause. ✅ Built, MVP-3. Single package
  (`com.evas.alerting`) for both device streams — the earlier
  component-diagram split into separate Alerting/Preemption components
  didn't survive contact with implementation, since both are driven by
  the same event and share the same window/cache machinery; see decisions
  below.
- **Audit** — read/query side for the event history Alerting produces (not
  a separate write path — Alerting persists its own `SignalPreemption`/
  `LamppostAlert` records; Audit is mainly about exposing query endpoints
  over that data later). ❌ Not built — post-MVP-3. The data exists and is
  queryable directly today (`psql`), just no dedicated read API yet.
- *(Policy and Auth components exist in the package layout but stay out of
  the data flow below until MVP-5/MVP-4 respectively — before that,
  thresholds are constants and endpoints are open.)*

## Core "Happy Path" Data Flow

All nine steps are built and verified end-to-end (MVP-1 through MVP-3).

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
5. A position update publishes a `PositionUpdatedEvent` after commit;
   **Alerting** reacts **asynchronously** (see wiring decision) by loading
   (or lazily building, on cache miss) the trip's two device windows — the
   next `signal-window-size` signals and `lamppost-window-size` lampposts
   ahead of the vehicle along its route, found via
   `TrafficSignalRepository`/`LamppostRepository#findNextAlongRoute`
   (`ST_DWithin` corridor filter, `ST_LineLocatePoint` ordering). First
   checks whether the vehicle has deviated from its stored route
   (`ST_Distance` past `deviation-threshold-m`) — if so, calls **Routing**
   for a fresh route and rebuilds both windows before doing anything else.
6. **Alerting** shifts each window (pops any device the vehicle has now
   passed, refilling one slot per pop via the same next-along-route
   query), then fires commands for whatever remains in trigger range:
   `LAMPPOST_APPROACH` every tick a lamppost is in-window (carries
   along-route distance, not ETA — no speed model to maintain), and
   `LAMPPOST_CLEAR` once when it's popped. Fire-once per (trip, lamppost)
   audit row (`LamppostAlert`), not one row per tick.
7. **Alerting** does the stateful equivalent for signals: `SIGNAL_
   PREEMPTION_ACTIVATE` once a signal enters trigger distance (idempotent
   per (trip, signal) via a partial-unique-on-`ACTIVE` index — audited as
   a `SignalPreemption` row), `SIGNAL_PREEMPTION_REVERT` once the vehicle
   passes it, the trip ends or pauses, or a safety-timeout sweep (a second
   scheduled job, independent of the stall watchdog) catches one stuck
   `ACTIVE` regardless of cause.
8. **Trip** flips to `ARRIVED` when the vehicle's *current position* comes
   within `arrivalThresholdMeters` (~30m, configurable) of `destination` —
   a destination-proximity check (Java Haversine, point-to-point), **not** a
   traveled-distance-along-route check, which would silently break on any
   real-world detour. Frees the vehicle to `AVAILABLE`, and publishes
   `TripTerminatedEvent(ARRIVED)` **instead of** a plain
   `PositionUpdatedEvent` — **Alerting** reacts by reverting every still-
   `ACTIVE` signal preemption and clearing every tracked lamppost, rather
   than running one more `evaluate()` tick against a trip that's already
   done. ✅
9. **Stall watchdog, two-tier**: if no position arrives for `staleTimeout`
   (~120s, configurable), the trip flips to `STALE` — **paused, not
   terminal**: the vehicle stays `ON_TRIP`, and the next position update
   resumes the trip straight back to `EN_ROUTE`. Only if silence continues
   past `expireTimeout` (~300s) does the trip flip to `EXPIRED` (terminal)
   and the vehicle get freed. This tolerates a real GPS blackout (tunnel,
   underpass, dense-urban canyon) without stranding a live emergency
   response, while a genuinely dead feed still eventually frees the
   vehicle. `STALE` also triggers `TripTerminatedEvent(PAUSED)` —
   **Alerting** reverts/clears everything tracked *safety-first*, the same
   as a real termination, since a signal must never sit force-green with
   no vehicle confirmed approaching; if the vehicle resumes, the next
   position re-activates whatever's back in trigger range as a fresh
   preemption (full multi-cycle audit trail preserved). One of two
   scheduled jobs in an otherwise-reactive backend (the other being
   Alerting's safety-timeout sweep) — each present precisely to detect an
   *absence*: of the position feed, and of a preemption's revert,
   respectively. ✅

Dispatcher can also cancel a trip (`EN_ROUTE → CANCELLED`) at any point —
built, MVP-1; this also publishes `TripTerminatedEvent(CANCELLED)`.

## Key Architectural Decisions

- **Component wiring — async, not direct** (reversed from an earlier draft):
  a position update publishes a `PositionUpdatedEvent` after commit, and
  Alerting reacts via `@Async("alertingExecutor")
  @TransactionalEventListener(AFTER_COMMIT)` — decoupled and on a separate,
  bounded thread pool. A prior draft of this HLD called for Position to
  invoke `AlertingService.evaluate()` *directly* (chosen then for
  breakpoint-level traceability). That's reversed: position ingestion must
  get a fast ack and must never fail or roll back because a downstream
  alert publish was slow or errored — losing GPS data over an alerting
  hiccup is backwards. Alerting only ever sees committed positions. The
  executor is bounded (`alerting.executor.*`) with `CallerRunsPolicy` —
  if alerting genuinely can't keep up, position ingestion backpressures
  rather than either an unbounded queue or a dropped alert. Trip
  termination/pause uses the same pattern with a second event,
  `TripTerminatedEvent(reason)` — one event with a reason discriminator
  (`CANCELLED`/`ARRIVED`/`EXPIRED`/`PAUSED`) rather than a separate event
  per case, since Alerting's reaction is the same shape in every case
  (revert/clear whatever's tracked) and only the audit reason and whether
  the cache entry survives differ. ✅ Built, MVP-3.
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
- **Alert transport**: RabbitMQ, one durable topic exchange
  (`evas.device.commands`). The backend only ever publishes — it never
  declares or binds a queue; each device (real, or a fake consumer in the
  external simulator) owns its own queue and binds it using its own
  `routing_key` column. Both `Lamppost` and `TrafficSignal` carry a
  `routing_key` now (MVP-3 added it to `TrafficSignal`; `Lamppost` always
  had one). Message shapes are JSON records with a `type` discriminator:
  `SIGNAL_PREEMPTION_ACTIVATE`/`SIGNAL_PREEMPTION_REVERT` (signals,
  edge-triggered) and `LAMPPOST_APPROACH`/`LAMPPOST_CLEAR` (lampposts,
  level-triggered) — see `docs/SIMULATOR-API-CONTRACT.md` W7a/W7b for the
  exact payloads. Publish always happens **inside** the same DB
  transaction as the audit-row write — if the publish throws (broker
  unreachable), the whole transaction rolls back and nothing is
  persisted either, so the next position tick simply retries. This is an
  accepted MVP simplification (log-and-continue, no outbox/retry queue)
  because it's not the backend's only safety net: devices are expected to
  carry their own hardware-level auto-revert (~180s) as a second,
  independent backstop, on top of the backend's own safety-timeout sweep
  (~120s) — standard practice for real traffic-signal preemption systems.
  **Gotcha**: `spring-amqp`'s `Jackson2JsonMessageConverter` is built
  against classic Jackson 2.x (`com.fasterxml.jackson`), a different
  major version from this app's own Jackson 3.x REST layer
  (`tools.jackson`, via `spring-boot-starter-jackson`) — the two
  `ObjectMapper` types are unrelated classes and can't be shared;
  `RabbitTopologyConfig` builds its own Jackson 2.x instance (with
  `JavaTimeModule` for `Instant` fields) rather than reusing the app's
  bean. **Also**: this Spring AMQP version's `RabbitAdmin` declares
  exchanges lazily, on first connection — not eagerly at boot — so an
  idle backend that's never published anything won't show its exchange
  in the RabbitMQ management UI until the first message goes out;
  confirmed empirically, not assumed. ✅ Built, MVP-3.
- **Windowed-cache model, not radius-per-tick queries** (MVP-3): each
  trip's alerting state is a rolling in-memory window (Caffeine,
  `TripAlertingCache`) of the next `signal-window-size`/
  `lamppost-window-size` devices ahead on the route — not a fresh
  `ST_DWithin` radius query every tick. Bounded, predictable per-tick
  work regardless of local device density; the window shifts (pops
  passed devices, refills one slot per pop) rather than being recomputed
  from scratch. Deliberately **reactive, not eager**: there's no
  "populate at trip creation" step — `TripWindowBuilder.buildFresh`
  anchors on the trip's current position (falling back to its origin if
  none yet) and is called wherever a cache entry turns out missing: the
  first position tick on a new trip, backend restart
  (`AlertingCacheWarmup`, on `ApplicationReadyEvent`), or resume from
  `STALE`. One code path for all three cases, and no new dependency
  from the `trip` package onto `alerting` (window population never
  needed to hook into `TripService.create`). In-memory only, no size
  bound or TTL — one entry per currently-active trip, bounded by how
  many emergency runs can be live at once, not by request volume. Never
  the source of truth for anything: a missing or stale entry is always
  safe to rebuild from the trip's own route + position in the database.
- **STALE reverts signal preemptions immediately, safety-first**: when a
  trip's feed goes silent past the soft timeout, every `ACTIVE`
  `SignalPreemption` for it is reverted right away (`reason=TRIP_PAUSED`)
  rather than held through the pause window — a signal must never sit
  force-green with no vehicle confirmed still approaching, even though
  this means a vehicle resuming within a few seconds may see the same
  signal flap green→red→green. This forced a real idempotency-model
  change: `signal_preemption`'s uniqueness went from a plain
  `UNIQUE(trip_id, signal_id)` to a **partial** unique index,
  `WHERE state = 'ACTIVE'` — "at most one `ACTIVE` preemption per
  (trip, signal) at a time" enforces dedup during a live approach while
  allowing the multi-cycle audit history a pause/resume produces
  (revert-then-reactivate against the same signal is a second, distinct
  audit row, not blocked by the first).
- **Signal reverts are DB-authoritative, not cache-driven — found via a
  real bug during MVP-3 verification.** `handleTripReset` (the listener
  for `TripTerminatedEvent`) originally reverted only what the in-memory
  window cache currently held. On backend restart, `AlertingCacheWarmup`
  (`ApplicationReadyEvent`) and the stall watchdog's first `@Scheduled`
  sweep have no defined ordering relative to each other — if a trip was
  already overdue at boot, the sweep could fire and publish its pause
  event *before* the warmup rebuilt that trip's cache, leaving
  `handleTripReset` looking at an empty cache and silently reverting
  nothing. Reproduced deliberately (activate signals, kill the app,
  restart with a short stale-timeout) and fixed: `handleTripReset` now
  queries `signal_preemption` directly for anything still `ACTIVE`,
  never trusting the cache for this safety-critical path. Lampposts keep
  using the cache for `LAMPPOST_CLEAR` — there's no DB-tracked "currently
  broadcasting" state to fall back on for them (their audit table is
  fire-once, not a state machine), and a missed `CLEAR` isn't a safety
  issue the way a stuck-green signal is.
- **Route math lives in PostGIS, not JTS, via three geometry-only native
  queries on `TripRepository`** (`fractionAlongRoute`, `routeLengthMeters`,
  `distanceToRouteMeters`) — none of them touch the `trip` table (no
  `FROM` clause; they take a route/point as parameters), they just have
  nowhere more natural to live. Same reasoning as `GeoPoints.distanceMeters`
  hand-rolling Haversine instead of using JTS's own (planar) `distance()`:
  JTS has no geodesic (real-world-metre) equivalent of `ST_LineLocatePoint`/
  `ST_Length`/`ST_Distance`, and PostGIS's geography-aware versions are the
  only correct source for "how far in actual metres." **Gotcha**: a native
  query parameter followed immediately by `::` (e.g. `:route::geography`)
  gets misparsed by Hibernate as one parameter literally named
  `route::geography` — fixed by using explicit `CAST(:route AS geography)`
  syntax instead of the `::` shorthand.
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
| 1.5.0 | 2026-09-20 | **MVP-3 (alerting) implemented.** Windowed-cache model (not radius-per-tick): a rolling per-trip window of upcoming devices per type, reactively built/rebuilt rather than populated at trip creation. Signals are edge-triggered (`ACTIVATE`/`REVERT`, stateful, audited), lampposts are level-triggered (`APPROACH` every tick in-window, `CLEAR` on pop, fire-once audit). Route-deviation detection re-routes and rebuilds both windows. `STALE` now safety-first-reverts every active preemption (new `TRIP_PAUSED` reason; `signal_preemption` uniqueness became a partial index on `ACTIVE` to allow the resulting multi-cycle audit trail). A second scheduled job, the safety-timeout sweep, reverts anything stuck `ACTIVE` regardless of cause. One real bug found and fixed during verification: `handleTripReset` was cache-driven and could silently no-op on a restart-timing race against `AlertingCacheWarmup` — made DB-authoritative for signal reverts instead. `com.evas.alerting` is a single package for both device streams, not the split Alerting/Preemption components sketched pre-implementation. |
