# High-Level Design — MVP-1 Delta

**Version:** 1.1.0 (builds on `docs/mvp-0/HLD.md` v1.0.0)
**Stage:** MVP-1 — implemented and verified end-to-end.

This file documents what MVP-1 added/changed relative to the foundational
HLD, rather than restating the whole system. See `docs/mvp-0/HLD.md` for
system context, the full component list, and the original happy-path flow.

## What's now real

- **Trip** component: full lifecycle (`EN_ROUTE`/`ARRIVED`/`CANCELLED`),
  creation with vehicle-availability + one-active-trip enforcement.
- **Routing** component: calls OSRM for a real road-snapped route, caches
  responses via Caffeine.
- The service layer pattern from `docs/mvp-0/LLD-data-model.md`'s "REST
  Layer" note is now demonstrated for real: `TripService` is the first
  place business logic actually lives, unlike Vehicle/Lamppost/TrafficSignal's
  pure CRUD.

## Correction to `docs/mvp-0/HLD.md`'s happy-path data flow

That doc's step 3 states Routing "also runs the device-along-route spatial
query... producing an ordered devices to watch list" as part of trip
creation. **This is deferred to MVP-3**, alongside the Alerting engine that
actually consumes it — nothing in MVP-1 or MVP-2 reads that list, so
building it now would be dead code. `docs/mvp-0/HLD.md` is left as the
historical snapshot of what was believed at that checkpoint rather than
edited in place — this file is the correction of record.

## Key decisions made this stage

- **Trip creation is fail-fast**: OSRM is called synchronously inside
  `POST /api/trips`. On failure, nothing is persisted — no `Trip` row, no
  vehicle status change — and the dispatcher just retries the request. No
  `ROUTING_FAILED` status, no nullable route columns. Verified: an
  unreachable OSRM host produces a 502 with the vehicle still `AVAILABLE`
  and zero trips created.
- **`TripStatus` includes all three values now** (`EN_ROUTE`/`ARRIVED`/
  `CANCELLED`), even though `ARRIVED` is unreachable until MVP-2's position
  feed exists — avoids a second migration touching the same CHECK
  constraint later. The one place this stage's design deliberately did
  *not* trim to only what's immediately used.
- **Trip fields were deliberately trimmed**: `route_distance_meters`,
  `route_duration_seconds`, `traveled_meters`, `simulated_speed_mps`, and
  `last_known_position` were all cut from the original draft — none had a
  consumer yet (they were headed for the MVP-2 simulator). Add back if and
  when something actually reads them.
- **Cache key is `(origin, destination)` only** — no vehicle-profile
  dimension. All current vehicle types route identically; a profile
  dimension was speculative scope for a case that doesn't exist.
- **One active trip per vehicle**: enforced at both the application layer
  (clean 409 with a real message) and the database layer (partial unique
  index) — the same belt-and-suspenders pattern already used for
  `Vehicle.call_sign`, `Lamppost.code`/`routing_key`, etc.
- **`CacheManager` is wired manually** (`CacheConfig`), not via Spring
  Boot's `spring.cache.type` autoconfiguration property. `@EnableCaching`
  and `CacheManager` are core Spring Framework (`spring-context`), and
  `CaffeineCacheManager` specifically lives in a *different* artifact
  (`spring-context-support`) than core `spring-context` — a genuine,
  long-standing module boundary (not a Spring Boot 4 quirk this time, but
  the same *kind* of "check what artifact actually provides this class"
  lesson the Flyway gotcha taught). Manual wiring sidesteps needing to
  guess whether Boot's own cache autoconfiguration activates cleanly on
  this version.
- **`RestClient`'s request factory is plain core Spring**
  (`SimpleClientHttpRequestFactory`), not one of Boot's higher-level
  client-factory helper classes — same reasoning: after two module-boundary
  surprises, prefer the class whose package has been stable the longest.

## Changelog

| Version | Date | Change |
|---|---|---|
| 1.1.0 | 2026-09-14 | MVP-1 implemented: Trip + Routing + Caching. Corrects mvp-0's happy-path step 3 (device-along-route query deferred to MVP-3). |
