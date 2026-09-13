# High-Level Design

**Version:** 1.0.0
**Stage:** MVP-0 (foundational — produced ahead of MVP-0 implementation)
**Status:** Design agreed; MVP-0 implementation not yet started.

This is the living HLD for the Emergency Vehicle Alert System backend. It
started as the foundational whole-system design (this version, 1.0.0) and
will grow a new dated entry in the changelog below whenever a later MVP
stage changes or extends it — see `docs/mvp-1/HLD.md` etc. once those exist.
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
- **Position simulator** → runs *inside* the backend process for now, behind
  an ingestion interface designed so a real GPS feed can implement the same
  interface later without touching downstream logic.
- **RabbitMQ** → the backend publishes lamppost alert instructions here;
  a real lamppost device (out of scope) would consume from its bound queue.
  No real device exists in this project — the API/messaging surface is
  designed as if one could.

## Internal Components

Maps 1:1 to the package-by-feature layout (`com.evas.<feature>`, see
ROADMAP.md):

- **Trip** — owns trip lifecycle (`CREATED → ROUTING → EN_ROUTE →
  ARRIVED/CANCELLED`), the entry point dispatcher calls hit.
- **Vehicle** — vehicle registry (CRUD, status lifecycle).
- **Device** — Lamppost + TrafficSignal registries (CRUD, active/inactive,
  spatial queries).
- **Routing** — talks to OSRM, stores route geometry, runs the
  "devices along this route" spatial query, owns the Caffeine route-response
  cache.
- **Position** — the ingestion interface + the in-process simulator
  implementation; advances a trip's `traveledMeters` on a timer and
  recomputes ETA-to-each-watched-device.
- **Alerting** — watches ETA-to-lamppost, fires/records alert instructions
  once threshold is crossed (idempotent per trip+device), publishes to
  RabbitMQ.
- **Preemption** — watches ETA-to-signal, fires force-green / reverts,
  records override events.
- **Audit** — read/query side for the event history Alerting/Preemption
  produce (not a separate write path — those components persist their own
  records; Audit is mainly about exposing query endpoints over that data
  later).
- *(Policy and Auth components exist in the package layout but stay out of
  the data flow below until MVP-5/MVP-4 respectively — before that,
  thresholds are constants and endpoints are open.)*

## Core "Happy Path" Data Flow (MVP-0 through MVP-3 scope)

1. Dispatcher calls **Trip** to create a trip (vehicle + origin +
   destination).
2. **Trip** asks **Routing** for a route. Routing checks the Caffeine cache
   (key: origin + destination + vehicle profile); on miss, calls OSRM,
   stores the route geometry + distance/duration, caches the response.
3. **Routing** also runs the device-along-route spatial query against
   **Device**, producing an ordered "devices to watch" list for this trip.
4. Trip status flips to `EN_ROUTE`; **Position** starts advancing a virtual
   vehicle along the stored route geometry on a timer (every 1–5s).
5. Each position tick, **Position** recomputes ETA to every remaining
   watched device for that trip.
6. **Alerting** checks ETA-to-lamppost against the (hardcoded, pre-MVP-5)
   threshold; once crossed, fires an alert instruction (published to
   RabbitMQ) + records it, and won't re-fire for that trip+lamppost pair.
7. **Preemption** does the equivalent for signals: force-green once within
   lead time, revert once the vehicle's position passes the signal's point
   on the route (or a safety timeout fires).
8. **Trip** flips to `ARRIVED` when the vehicle's *current position* comes
   within a threshold distance (e.g. ~20–50m, TBD when built) of the trip's
   `destination` point — **not** when `traveledMeters` reaches
   `routeDistanceMeters`. A traveled-distance-along-planned-route check
   silently breaks the moment a real vehicle takes a detour (obstruction,
   driver choice, traffic), since it assumes the vehicle stays exactly on
   the OSRM-planned polyline. A destination-proximity check (simple
   point-to-point distance, independent of the route geometry) is
   deviation-tolerant and doesn't require full mid-trip re-routing support
   (still a stretch goal) to be correct. `traveledMeters` remains useful for
   ETA-to-device ordering/calculation, but is no longer the arrival signal.
   **Position** stops advancing the trip and any still-pending
   alerts/overrides for that trip are cleared/reverted once `ARRIVED` is
   reached.

> **Flagged implication for Trip's LLD (MVP-1, not yet designed):** the
> destination-proximity arrival check needs the vehicle's actual last-known
> position, not just `traveledMeters`. `Trip` will likely need a
> `lastKnownPosition` (Point) field alongside `traveledMeters`.

## Key Architectural Decisions

- **Component wiring**: Position's tick handler calls **directly** into
  `AlertingService.evaluate(trip)` and `PreemptionService.evaluate(trip)`
  after updating ETA — no internal event bus for now. Chosen over Spring
  application events because a direct call chain is easier to
  trace/debug with breakpoints while still learning how the pieces
  connect; the coupling cost is small at this scale. Revisit if/when a
  third reactor needs to hook into position updates and the direct-call
  list starts feeling unwieldy.
- **Caching**: Spring's cache abstraction (`@Cacheable`/`@CacheEvict`)
  backed by Caffeine (in-memory), fitting the single-instance/no-HA
  deployment model. Swappable to Redis later by changing only the
  `CacheManager` bean.
- **Alert transport**: RabbitMQ. A topic exchange (e.g. `lamppost.alerts`),
  each `Lamppost.routingKey` binds its own queue to it. Full
  exchange/queue/binding topology is a later LLD chunk (belongs with the
  Alerting engine, MVP-3) — MVP-0 only needs the `routingKey` field to
  exist on `Lamppost`.
- **Schema management**: Flyway, versioned migrations — not Hibernate
  `ddl-auto`. See `docs/mvp-0/LLD-vehicle.md` for the migration-ownership
  note.

## Changelog

| Version | Date | Change |
|---|---|---|
| 1.0.0 | 2026-09-14 | Initial HLD: system context, components, happy-path data flow, wiring/caching/messaging/schema decisions. |
