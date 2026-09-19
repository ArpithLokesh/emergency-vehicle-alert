# LLD: Position Tracking (MVP-2)

**HLD version this was written against:** 1.4.0 (`docs/HLD.md`)
**Status:** Implemented and verified end-to-end (curl + timed stall/expire/resume tests).

MVP-2 makes the backend reliably track a vehicle through a trip's lifecycle:
ingest live positions, detect arrival, detect a stalled feed. **No
device/ETA logic** — that's alerting (MVP-3). The vehicle simulator is a
separate external app (see `docs/SIMULATOR-API-CONTRACT.md`); the backend is
purely reactive to its position feed, save for one watchdog.

## Schema
- `V3__add_trip_position_tracking.sql`: `trip.last_known_position
  geometry(Point,4326)` (nullable) — latest raw GPS point.
  `trip.last_position_at timestamptz` (nullable) — arrival time of the last
  update; drives stall detection. `trip_status_valid` CHECK dropped +
  recreated to admit `STALE`.
- `V4__add_trip_expired_status.sql`: `trip_status_valid` recreated again to
  add `EXPIRED`. `idx_trip_vehicle_active` (the one-active-trip-per-vehicle
  partial unique index) extended from `WHERE status = 'EN_ROUTE'` to
  `WHERE status IN ('EN_ROUTE', 'STALE')` — a vehicle with a paused
  (`STALE`) trip is still not free for a new dispatch.

`Trip` entity gains matching nullable fields; `TripStatus` gains `STALE`
then `EXPIRED`. `ddl-auto=validate` confirms entity↔schema match on boot.

## Config — `TrackingProperties` (`tracking.*`)
Record + compact-constructor defaults, same pattern as `RoutingProperties`:
`arrivalThresholdMeters` (30), `staleTimeout` (120s), `expireTimeout`
(300s), `staleCheckInterval` (15s). Two-tier by design (see "Stall
watchdog, two-tier" below) — `staleTimeout` was originally 45s/terminal,
revised to 120s/soft once the two-tier model replaced it.

## Position ingestion (write path)
- `POST /api/trips/{id}/position`, body `GeoPointDto` (reused, already
  validated -90..90 / -180..180). Returns `PositionUpdateResponse`
  `{tripId, status, lastKnownPosition, completedAt}` — a minimal receipt,
  **no device data** (the vehicle is a dumb emitter).
- `TripService.updatePosition` `@Transactional`: 404 if unknown; 409 if
  status is not `EN_ROUTE` or `STALE` (i.e. `ARRIVED`/`CANCELLED`/`EXPIRED`
  all reject); if the trip was `STALE`, flips it back to `EN_ROUTE` first
  (transparent resume — no separate "resume" call, the client just keeps
  POSTing) and logs the resume; sets `lastKnownPosition` +
  `lastPositionAt=now`; arrival check → if within `arrivalThresholdMeters`
  of destination, flip `ARRIVED`, set `completedAt`, free the vehicle
  (mirrors `cancel()`).
- Arrival distance = `GeoPoints.distanceMeters` (Haversine, Java). No spatial
  query on the write path; JTS has no geodesic distance so it's hand-rolled.

## Stall watchdog, two-tier
Revised from the original single-tier design (`STALE` at 45s, terminal)
after real-world review: a genuine GPS blackout (tunnel, underpass,
dense-urban canyon) commonly runs 60–120s and shouldn't strand a live
trip. Two thresholds now:

- **Soft (`staleTimeout`, ~120s)** → `STALE`. **Not terminal** — the
  vehicle stays `ON_TRIP`, no `completedAt` is set, and the trip simply
  waits. The next position update (see write path above) resumes it.
- **Hard (`expireTimeout`, ~300s)** → `EXPIRED`. Terminal — `completedAt`
  set, vehicle freed. Reachable directly from `EN_ROUTE` too (not only via
  `STALE`) — a delayed sweep that finds a trip past both cutoffs at once
  goes straight to `EXPIRED`, skipping the flicker through `STALE`.

Implementation:
- `@EnableScheduling` on the main class (a must-enable, like `@EnableCaching`).
- `TripStaleWatchdog.sweep()` `@Scheduled(fixedDelayString =
  "${tracking.stale-check-interval}")` checks **expiry before staleness**
  each tick (so the "cross both cutoffs at once" case above resolves
  correctly): `TripRepository.findOverdueForExpiry(expireCutoff)` (JPQL,
  `status IN (EN_ROUTE, STALE) AND COALESCE(lastPositionAt, createdAt) <
  :cutoff`) → `tripService.markExpired(id, cutoff)` per trip; then
  `findOverdueForStale(staleCutoff)` (same shape, `status = EN_ROUTE`) →
  `tripService.markStale(id, cutoff)` per trip. The COALESCE catches both
  "went silent mid-trip" and "never sent a first position" in both queries.
- Both `markStale` and `markExpired` live in `TripService` (a **separate
  bean** from the watchdog, so `@Transactional` actually applies — a
  self-call wouldn't), are **per-trip transactional** (one bad row can't
  abort the sweep), and re-check status + the cutoff inside the tx (a
  position may have landed, or another terminal path may have fired,
  between the scan and the lock) — same pessimistic-lock pattern as
  `updatePosition`/`cancel` (`TripRepository#findByIdForUpdate`).
- `cancel()` now accepts `EN_ROUTE` or `STALE` — a dispatcher can cancel a
  currently-paused trip, not just an active one.

## `TripResponse` gains `lastKnownPosition`
Added to the record + `from()`, so GET/list/create/cancel all carry it. This
is how MVP-2 is observed: POST positions, `GET /api/trips/{id}` shows the
point moving and status `EN_ROUTE → ARRIVED` (or `→ STALE → EN_ROUTE`, or
`→ STALE → EXPIRED`).

## Verification (all passed)
- Flyway V3 + V4 applied; `ddl-auto=validate` clean.
- POST mid-route position → 200 `EN_ROUTE`; GET shows `lastKnownPosition`.
- POST at destination → `ARRIVED`, vehicle `AVAILABLE`, subsequent POST 409.
- Soft stall: one position then silence past `staleTimeout` → `STALE`,
  vehicle stays `ON_TRIP` (confirmed via GET), a second dispatch for the
  same vehicle correctly rejected 409.
- Resume: position POSTed to a `STALE` trip → 200 `EN_ROUTE` (confirmed via
  log line "Trip ... resumed"), tracking continues normally.
- Hard expiry: continued silence past `expireTimeout` → `EXPIRED`, vehicle
  freed, subsequent POST 409 — confirmed via matching log timestamps
  (position received at T, EXPIRED fired at exactly T + expireTimeout).
- Cancel from `STALE` → 200 `CANCELLED`, vehicle freed.
- All of the above run live with short overridden timeouts (8s/20s/3s via
  env vars) for a fast test cycle, then re-verified with production
  defaults (120s/300s) for a clean boot.

## Changelog

| Date | Change | Reason |
|---|---|---|
| 2026-09-19 | Implemented position ingestion, destination-proximity arrival, and the stall watchdog (`STALE` status, single-tier terminal); Trip gains `last_known_position`/`last_position_at`; `TrackingProperties` added. | MVP-2 per `~/.claude/plans/concurrent-wishing-cocke.md`. |
| 2026-09-20 | Stall detection made two-tier: `STALE` is now a paused/recoverable state (vehicle stays `ON_TRIP`, position POST resumes it); `EXPIRED` added as the actual terminal outcome. `cancel()` accepts `STALE`. Migration `V4`. | Real-world review: a 45s single-tier terminal STALE would strand a trip on any GPS blackout longer than 45s (tunnels, underpasses commonly exceed this). |
