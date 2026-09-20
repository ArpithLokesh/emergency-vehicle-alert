# Roadmap & Source of Truth

This file is the living record of what this project is, what's been decided, and
what's next. Update it as decisions get made — it's the thing to read to get
back up to speed, not the chat history.

## How we work

The loop we repeat for every piece of work:

1. **Design in a small chunk** — one subsystem or entity group at a time,
   discussed back and forth until agreed. Never a big document dump.
2. **Record it** — `docs/mvp-N/HLD.md` if the architecture changed,
   `docs/mvp-N/LLD-*.md` for the detail. Every doc carries a changelog.
3. **Claude writes the code**, including the repetitive boilerplate
   (entities, repositories, DTOs, controllers), and explains each new
   pattern once — what it does and why it's needed. Arpith reviews every
   change closely and makes manual edits where he wants something
   different.
4. **Verify it actually runs** — boot the app, hit it with curl/Postman,
   check the DB directly with `psql`.
5. **Commit** — one coherent commit per meaningful unit.
6. **Update status** — the "Current status" section below, plus doc
   changelogs.
7. **Claude flags implied changes** — if a code change means a design doc
   is now stale or has a knock-on effect that isn't obvious, say so
   explicitly rather than silently patching docs.

## What this is

Backend for an Emergency Vehicle Alert System: an ambulance/police/fire vehicle
is dispatched, the backend tracks its route and position, and triggers alerts
to roadside IoT devices as it approaches — lampposts broadcast a "make way"
alert (via LiFi, out of scope here), and traffic signals preempt to green
ahead of arrival. Hardware/IoT/LiFi is explicitly out of scope; this is the
backend service only.

## Locked product decisions

- **Trip creation**: human dispatcher console only. No 108/112 integration, no driver app.
- **Tenancy**: single traffic authority per deployment. No multi-tenant isolation.
- **Alert channels**: LiFi-to-car only. No companion app, no public signage.
- **Device management**: basic registry (location + active/inactive). No provisioning/firmware/heartbeat.
- **Signal conflicts** (two vehicles, one signal): out of scope — documented limitation, not solved.
- **Policy values** (alert ETA threshold, preemption lead time): configurable per authority + vehicle type — built in MVP-5, hardcoded before that.
- **Vehicle types**: `AMBULANCE`, `POLICE`, `FIRE_ENGINE` (closed enum for now). Vehicle also has a `status` lifecycle (`AVAILABLE`/`ON_TRIP`/`MAINTENANCE`/`RETIRED`), not a boolean active flag — see `docs/mvp-0/LLD-data-model.md`.
- **Lamppost** and **TrafficSignal** are separate entities, not a unified "roadside unit" type.
- All geodata: PostGIS geometry columns, SRID 4326 (WGS84). Note: JTS/PostGIS coordinate order is `(lng, lat)`. Geometry over REST is plain `{lat, lng}`, not GeoJSON.
- **Routing/ETA**: delegated to OSRM over HTTP. No in-house routing algorithm.
- **Trip arrival detection**: destination-proximity check (current position within a threshold of the trip's destination point), **not** traveled-distance-along-planned-route — the latter breaks on any real-world detour. See `docs/HLD.md` happy-path step 8.
- **Vehicle position source**: simulator for now, behind an ingestion interface so real GPS can swap in later without touching downstream logic.
- **Alert/command delivery transport**: **RabbitMQ** — one durable topic exchange (`evas.device.commands`); the backend only ever publishes, each device (Lamppost or TrafficSignal) binds its own queue via its `routingKey` field. Built, MVP-3. See `docs/mvp-3/LLD-alerting.md`.
- **Caching**: Spring's cache abstraction (`@Cacheable`/`@CacheEvict`) backed by **Caffeine** (in-memory) now — fits the single-instance/no-HA deployment model. Swappable to Redis later by changing only the `CacheManager` bean; business code using `@Cacheable` doesn't change.
- **Schema management**: **Flyway**, versioned migrations — not Hibernate `ddl-auto`. Migration SQL content is user-written (same code-ownership boundary as entities), tooling scaffolded by Claude.

## Stack

- Spring Boot 4.1.1, Java 21, Maven (via `./mvnw`)
- Postgres + PostGIS (Docker container `evas-postgres`; see `docker-compose.yml` — started manually with `docker run` since the compose plugin isn't installed locally)
- Hibernate Spatial for JPA↔PostGIS geometry mapping (confirmed working)
- OSRM for routing (public demo instance for now: `https://router.project-osrm.org`)
- RabbitMQ (Docker container `evas-rabbitmq`) for alert delivery
- Flyway for versioned DB schema migrations (replaces Hibernate `ddl-auto`) — via `spring-boot-starter-flyway`, not raw `flyway-core` (Spring Boot 4 gotcha, see `docs/mvp-0/LLD-data-model.md`)
- Caffeine for in-memory caching (via Spring's cache abstraction)

## Documentation

**HLD is a single, running document** (`docs/HLD.md`) — the current
end-to-end architecture, versioned, updated in place with a changelog
tracking what changed and why. Not split per MVP stage: an earlier attempt
to fragment it into `docs/mvp-N/HLD.md` delta files made the current
design hard to see without reading and merging several files, and was
reverted.

**LLDs stay organized per MVP stage** (`docs/mvp-N/LLD-*.md`) — that split
works fine, since each LLD is genuinely scoped to what that stage builds:

```
docs/
  HLD.md                        — single end-to-end HLD, versioned, changelog
  mvp-0/
    LLD-data-model.md           — all MVP-0 entities (Vehicle, Lamppost, LamppostAlert, TrafficSignal) in one doc
  mvp-1/
    LLD-trip-and-routing.md     — Trip entity, TripService, RoutingService, CacheConfig
  mvp-2/
    LLD-position-tracking.md    — position ingestion, arrival detection, stall watchdog
  mvp-3/
    LLD-alerting.md             — windowed device cache, signal preemption, lamppost broadcast, RabbitMQ topology
SIMULATOR-API-CONTRACT.md       — boundary contract for the external vehicle/device simulator
FUTURE_UPGRADES.md              — backlog of deferred-but-planned ideas (project root)
```

Each `LLD-*.md` has its own changelog table and states which `HLD.md`
version it was written against. When a code change has a downstream
implication on a design doc that might not be obvious, Claude flags it
explicitly rather than silently updating docs.

## MVP Roadmap

- [x] **MVP-0 — Domain skeleton.** `Vehicle`, `Lamppost`, `TrafficSignal` as CRUD-able JPA entities. No trips, no routing. Demo: boot app, CRUD via REST/Postman, geometry columns round-trip correctly in Postgres.
- [x] **MVP-1 — Trip creation + OSRM routing + caching.** Dispatcher creates a trip (vehicle + origin + destination); backend calls OSRM, stores route geometry. OSRM responses cached via Caffeine, keyed on (origin, destination) — no vehicle-profile dimension, that was speculative scope. Device-along-route spatial query deferred to MVP-3 (nothing consumes it until Alerting exists). Demo: route returned and visualizable — verified with real OSRM calls against real Bengaluru coordinates.
- [x] **MVP-2 — Position feed: tracking, arrival, stall detection.** An **external** simulator (separate project) POSTs vehicle positions to `POST /api/trips/{id}/position`; backend persists `last_known_position`, detects arrival (destination-proximity → `ARRIVED`, frees vehicle), and runs a two-tier stall watchdog (no position for ~120s → `STALE`, paused/recoverable, vehicle stays `ON_TRIP`; still silent past ~300s → `EXPIRED`, terminal, frees vehicle). A position update to a `STALE` trip resumes it transparently back to `EN_ROUTE`. Reactive backend, no in-process simulator. Live-ETA/device-proximity was moved *out* of here — it's alerting-internal (MVP-3), not a public endpoint. Demo: POST positions, watch `GET /api/trips/{id}` show the vehicle move and reach `ARRIVED`, or pause to `STALE` and either resume or `EXPIRE` on continued silence. Verified end-to-end incl. timed stall/resume/expiry tests.
- [x] **MVP-3 — Alerting + signal preemption, with audit trail.** Reacts **asynchronously** (post-commit `PositionUpdatedEvent`/`TripTerminatedEvent`) to position updates and trip termination/pause. Maintains a rolling per-trip **window** (not a radius-per-tick query) of the next few devices ahead per type, using `ST_DWithin`+`ST_LineLocatePoint` to find and order candidates. Fires the two device command streams to RabbitMQ (`evas.device.commands`) — signal force-green/revert (W7a, edge-triggered, audited as `SignalPreemption`) and lamppost broadcast (W7b, level-triggered, audited as `LamppostAlert`) — with a safety-timeout sweep as backstop. Thresholds hardcoded (pre-MVP-5). First true end-to-end demo of the product concept.
- [ ] **MVP-4 — Auth & roles.** Login required; `DISPATCHER` vs `TRAFFIC_AUTHORITY_ADMIN`. Device-facing endpoints get separate device-credential auth (never reuse a user token for device commands).
- [ ] **MVP-5 — Configurable policy.** Admin can view/edit alert threshold + preemption lead time per authority and per vehicle type, no redeploy needed. Alerting/Preemption engines read from policy store instead of constants.
- [ ] **MVP-6 (stretch) — Hardening.** Safety-timeout reversion for stuck signals, graceful OSRM failure/retry handling, position-feed stall detection, Actuator health + correlation-ID logging, retention cleanup for raw position ticks.

Truly out of scope, even post-MVP-6: multi-vehicle signal conflict resolution,
real GPS/hardware integration, driver-facing app, 108/112 integration.

## Package structure (package-by-feature, under `com.evas`)

```
trip/       — Trip entity, lifecycle, position ingestion, stall watchdog
vehicle/    — Vehicle entity/registry
device/     — Lamppost + TrafficSignal entities/registries
routing/    — OSRM client, route storage, ETA calculation, caching
alerting/   — device windows/cache, signal preemption + lamppost broadcast,
              RabbitMQ topology, safety-timeout sweep (both device command
              streams — a separate preemption/ package was sketched
              pre-implementation but never split out: both are driven by
              the same event and share the same window/cache machinery)
policy/     — configuration/settings entities and lookup (from MVP-5)
auth/       — Spring Security config, users, roles (from MVP-4)
audit/      — history entities and query endpoints
common/     — cross-cutting utilities (PostGIS helpers, base exceptions)
```

(Position ingestion never became its own `position/` package — it's part
of `trip/`, since `Trip` already owns the row `last_known_position` writes
to and the stall watchdog already lives alongside `TripService`.)

Each feature package holds its own `Foo.java` / `FooController.java` /
`FooService.java` / `FooRepository.java` together, rather than splitting
across layered `controller/`/`service/`/`repository/` packages.

## Current status

**MVP-0 through MVP-3 are complete.**

MVP-0: entities, repositories, and REST controllers for `Vehicle`,
`Lamppost`, `TrafficSignal`, verified end-to-end with curl.

MVP-1: `Trip` + `TripService` + `TripController` + `RoutingService` +
Caffeine caching, migration `V2` applied. Verified end-to-end: real OSRM
routing against real coordinates, one-active-trip-per-vehicle rejection
(app + DB level), cancel flow (frees the vehicle), cache-hit timing
(~15ms cached vs ~2s fresh), and OSRM-failure fail-fast behavior (502,
nothing persisted). See `docs/mvp-1/` for the full design record.

MVP-2: position ingestion (`POST /api/trips/{id}/position`), destination-
proximity arrival, and a two-tier stall watchdog (`STALE` paused/
recoverable, `EXPIRED` terminal), migrations `V3`+`V4` applied.
`TrackingProperties` config; `@EnableScheduling` for the watchdog.
Verified end-to-end: position updates move `last_known_position`, arrival
frees the vehicle, terminal trips reject positions (409), a `STALE` trip
resumes transparently on the next position, and timed tests confirmed
`STALE` at ~120s and `EXPIRED` at ~300s of continued silence. Simulator
is external (separate project); backend is reactive except the watchdog.
See `docs/mvp-2/LLD-position-tracking.md`.

Infra verified: Postgres+PostGIS (`evas-postgres`) and RabbitMQ
(`evas-rabbitmq`) running in Docker.

MVP-3: `com.evas.alerting` — a rolling per-trip device window (Caffeine
cache, `TripAlertingCache`/`TripWindowBuilder`), reactively built (no
eager population at trip creation) via
`TrafficSignalRepository`/`LamppostRepository#findNextAlongRoute`
(`ST_DWithin` corridor + `ST_LineLocatePoint` ordering, both native
queries against real PostGIS geometry). `AlertingService.evaluate()`
reacts to `PositionUpdatedEvent`: deviation-detect + re-route, shift each
window (pop-passed via an atomic conditional revert, refill one slot per
pop), fire `SIGNAL_PREEMPTION_ACTIVATE`/`LAMPPOST_APPROACH` for whatever's
in trigger range. `AlertingService.handleTripReset()` reacts to
`TripTerminatedEvent` (CANCELLED/ARRIVED/EXPIRED/PAUSED) by reverting
every ACTIVE preemption and clearing every tracked lamppost —
**DB-authoritative** for signals (queries `signal_preemption` directly,
never trusts the in-memory cache for this safety-critical path — see the
bug below). `SignalPreemptionSafetyWatchdog` is a second scheduled job,
reverting anything stuck ACTIVE past `alerting.safety-timeout` (~120s)
regardless of cause. Migrations `V5`+`V6` applied (`signal_preemption`,
`lamppost_alert`, `TrafficSignal.routing_key`, `WINDOW_CHANGED` revert
reason). `TrafficSignal`/`LamppostCreateRequest` both require
`routingKey` now.

Verified end-to-end against real seeded Bengaluru signals with a debug
RabbitMQ consumer bound to the exchange: full happy path (dozens of real
signals, each exactly one activate+revert cycle; lamppost APPROACH
stream + CLEAR), terminal fan-out (ARRIVED/CANCELLED), pause/resume via
STALE (multi-cycle audit trail — same signal re-preempted as a fresh row
after resuming), route deviation (a 367m deviation triggered a real
re-route + window rebuild), RabbitMQ-down (publish failure rolled back
cleanly, zero orphaned rows, position ingestion unaffected), and the
safety-timeout sweep (killed the app mid-preemption, confirmed revert on
restart). See `docs/mvp-3/LLD-alerting.md`.

**Two real bugs found and fixed during MVP-3 verification**: a Hibernate
native-query parsing gotcha (`:route::geography` parsed as one parameter
literally named `route::geography` — fixed with explicit `CAST(...)`
syntax), and a genuine restart-timing race in `handleTripReset` (the
cache-warmup listener and the stall watchdog's first sweep have no
defined ordering, so a trip already overdue at boot could get its pause
event processed against an empty cache — fixed by making signal reverts
query the database directly instead of trusting the cache).

**Real test data**: 405 real traffic signals from OpenStreetMap (central
Bengaluru, MG Road/Indiranagar corridor) loaded via
`scripts/import-osm-traffic-signals.py`, which replays an Overpass export
through `POST /api/traffic-signals` — no new application code, exercises
the real endpoint. Re-runnable and idempotent (re-running skips existing
`TS-OSM-<id>` codes rather than duplicating). Cached fetch in
`scripts/data/` since the public Overpass API proved occasionally
slow/rate-limited. Lampposts are not seeded in bulk (no import script
exists for them yet — see `FUTURE_UPGRADES.md`).

**Next**: MVP-4 — auth & roles.
