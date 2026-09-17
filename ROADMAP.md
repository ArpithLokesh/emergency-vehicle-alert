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
- **Trip arrival detection**: destination-proximity check (current position within a threshold of the trip's destination point), **not** traveled-distance-along-planned-route — the latter breaks on any real-world detour. See `docs/mvp-0/HLD.md` happy-path step 8.
- **Vehicle position source**: simulator for now, behind an ingestion interface so real GPS can swap in later without touching downstream logic.
- **Alert/command delivery transport**: **RabbitMQ**, locked in — a topic exchange with each Lamppost binding its own queue via a `routingKey` field. Full topology detail deferred to MVP-3. See `docs/mvp-0/LLD-data-model.md`.
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

Design docs live in `docs/<mvp-stage>/`, organized by MVP stage/functionality
rather than split into separate hld/ vs lld/ folders:

```
docs/
  mvp-0/
    HLD.md              — versioned (starts 1.0.0), whole-system design + changelog
    LLD-data-model.md   — all MVP-0 entities (Vehicle, Lamppost, LamppostAlert, TrafficSignal) in one doc
  mvp-1/
    HLD.md                    — delta from mvp-0 (v1.1.0): Trip + Routing + Caching, corrects mvp-0's device-along-route timing
    LLD-trip-and-routing.md   — Trip entity, TripService, RoutingService, CacheConfig
  mvp-2/ (etc., created as each stage is designed)
FUTURE_UPGRADES.md       — backlog of deferred-but-planned ideas (project root)
```

Each `HLD.md` carries a version number and changelog; each `LLD-*.md` has its
own changelog table. When a code change has a downstream implication on a
design doc that might not be obvious, Claude flags it explicitly rather than
silently updating docs.

## MVP Roadmap

- [x] **MVP-0 — Domain skeleton.** `Vehicle`, `Lamppost`, `TrafficSignal` as CRUD-able JPA entities. No trips, no routing. Demo: boot app, CRUD via REST/Postman, geometry columns round-trip correctly in Postgres.
- [x] **MVP-1 — Trip creation + OSRM routing + caching.** Dispatcher creates a trip (vehicle + origin + destination); backend calls OSRM, stores route geometry. OSRM responses cached via Caffeine, keyed on (origin, destination) — no vehicle-profile dimension, that was speculative scope. Device-along-route spatial query deferred to MVP-3 (nothing consumes it until Alerting exists). Demo: route returned and visualizable — verified with real OSRM calls against real Bengaluru coordinates.
- [ ] **MVP-2 — Simulated position feed + live ETA.** Starting a trip kicks off a background simulator advancing a virtual vehicle along the route every few seconds. ETA to each upstream device recomputes live. Demo: shrinking ETAs as the simulated vehicle approaches devices.
- [ ] **MVP-3 — Alerting + signal preemption, with audit trail.** Crossing the (still-hardcoded) threshold triggers lamppost alert / signal force-green, observable via a per-device "pending instructions" endpoint + history of triggered alerts and forced/reverted signals. **Decide alert transport mechanism here.** First true end-to-end demo of the product concept.
- [ ] **MVP-4 — Auth & roles.** Login required; `DISPATCHER` vs `TRAFFIC_AUTHORITY_ADMIN`. Device-facing endpoints get separate device-credential auth (never reuse a user token for device commands).
- [ ] **MVP-5 — Configurable policy.** Admin can view/edit alert threshold + preemption lead time per authority and per vehicle type, no redeploy needed. Alerting/Preemption engines read from policy store instead of constants.
- [ ] **MVP-6 (stretch) — Hardening.** Safety-timeout reversion for stuck signals, graceful OSRM failure/retry handling, position-feed stall detection, Actuator health + correlation-ID logging, retention cleanup for raw position ticks.

Truly out of scope, even post-MVP-6: multi-vehicle signal conflict resolution,
real GPS/hardware integration, driver-facing app, 108/112 integration.

## Package structure (package-by-feature, under `com.evas`)

```
trip/       — Trip entity, lifecycle, dispatch use cases
vehicle/    — Vehicle entity/registry
device/     — Lamppost + TrafficSignal entities/registries
routing/    — OSRM client, route storage, ETA calculation, caching
position/   — position ingestion interface + simulator implementation
alerting/   — lamppost alert evaluation/triggering
preemption/ — signal preemption evaluation/triggering
policy/     — configuration/settings entities and lookup (from MVP-5)
auth/       — Spring Security config, users, roles (from MVP-4)
audit/      — history entities and query endpoints
common/     — cross-cutting utilities (PostGIS helpers, base exceptions)
```

Each feature package holds its own `Foo.java` / `FooController.java` /
`FooService.java` / `FooRepository.java` together, rather than splitting
across layered `controller/`/`service/`/`repository/` packages.

## Current status

**MVP-0 and MVP-1 are complete.**

MVP-0: entities, repositories, and REST controllers for `Vehicle`,
`Lamppost`, `TrafficSignal`, verified end-to-end with curl.

MVP-1: `Trip` + `TripService` + `TripController` + `RoutingService` +
Caffeine caching, migration `V2` applied. Verified end-to-end: real OSRM
routing against real coordinates, one-active-trip-per-vehicle rejection
(app + DB level), cancel flow (frees the vehicle), cache-hit timing
(~15ms cached vs ~2s fresh), and OSRM-failure fail-fast behavior (502,
nothing persisted). See `docs/mvp-1/` for the full design record.

Infra verified: Postgres+PostGIS (`evas-postgres`) and RabbitMQ
(`evas-rabbitmq`) running in Docker.

**Next**: MVP-2 — simulated position feed + live ETA-to-device
recalculation.
