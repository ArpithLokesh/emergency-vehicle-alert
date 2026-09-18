# LLD: Position Tracking (MVP-2)

**HLD version this was written against:** 1.3.0 (`docs/HLD.md`)
**Status:** Implemented and verified end-to-end (curl + a timed stall test).

MVP-2 makes the backend reliably track a vehicle through a trip's lifecycle:
ingest live positions, detect arrival, detect a stalled feed. **No
device/ETA logic** — that's alerting (MVP-3). The vehicle simulator is a
separate external app (see `docs/SIMULATOR-API-CONTRACT.md`); the backend is
purely reactive to its position feed, save for one watchdog.

## Schema — `V3__add_trip_position_tracking.sql`
- `trip.last_known_position geometry(Point,4326)` (nullable) — latest raw GPS point.
- `trip.last_position_at timestamptz` (nullable) — arrival time of the last
  update; drives stall detection.
- `trip_status_valid` CHECK dropped + recreated to admit `STALE`.

`Trip` entity gains matching nullable fields; `TripStatus` gains `STALE`.
`ddl-auto=validate` confirms entity↔schema match on boot.

## Config — `TrackingProperties` (`tracking.*`)
Record + compact-constructor defaults, same pattern as `RoutingProperties`:
`arrivalThresholdMeters` (30), `staleTimeout` (45s), `staleCheckInterval`
(15s). `staleTimeout` is generous on purpose — a brief GPS gap (tunnel,
urban canyon) must not kill a live trip.

## Position ingestion (write path)
- `POST /api/trips/{id}/position`, body `GeoPointDto` (reused, already
  validated -90..90 / -180..180). Returns `PositionUpdateResponse`
  `{tripId, status, lastKnownPosition, completedAt}` — a minimal receipt,
  **no device data** (the vehicle is a dumb emitter).
- `TripService.updatePosition` `@Transactional`: 404 if unknown; 409 if not
  `EN_ROUTE`; set `lastKnownPosition` + `lastPositionAt=now`; arrival check
  → if within `arrivalThresholdMeters` of destination, flip `ARRIVED`, set
  `completedAt`, free the vehicle (mirrors `cancel()`).
- Arrival distance = `GeoPoints.distanceMeters` (Haversine, Java). No spatial
  query on the write path; JTS has no geodesic distance so it's hand-rolled.

## Stall watchdog
- `@EnableScheduling` on the main class (a must-enable, like `@EnableCaching`).
- `TripStaleWatchdog.sweep()` `@Scheduled(fixedDelayString =
  "${tracking.stale-check-interval}")`: `cutoff = now - staleTimeout`;
  `TripRepository.findStale(cutoff)` (JPQL, `status = EN_ROUTE AND
  COALESCE(lastPositionAt, createdAt) < :cutoff` — the COALESCE catches both
  "went silent mid-trip" and "never sent a first position", and can't be a
  derived method name); for each, `tripService.markStale(id)`.
- `markStale` lives in `TripService` (a **separate bean** from the watchdog,
  so its `@Transactional` proxy actually applies — a self-call wouldn't), is
  **per-trip transactional** (one bad row can't abort the sweep), and
  re-checks `EN_ROUTE` inside the tx (the trip may have ARRIVED/CANCELLED
  between scan and mark). Sets `STALE` + `completedAt`, frees the vehicle.
- `STALE` is terminal → a late position POST returns 409; the vehicle is
  already freed → redispatch. MVP-3 will add signal-revert on the same path.

## `TripResponse` gains `lastKnownPosition`
Added to the record + `from()`, so GET/list/create/cancel all carry it. This
is how MVP-2 is observed: POST positions, `GET /api/trips/{id}` shows the
point moving and status `EN_ROUTE → ARRIVED` (or `→ STALE`).

## Verification (all passed)
- Flyway V3 applied; `ddl-auto=validate` clean.
- POST mid-route position → 200 `EN_ROUTE`; GET shows `lastKnownPosition`.
- POST at destination → `ARRIVED`, vehicle `AVAILABLE`, subsequent POST 409.
- Stall: one position then silence → `STALE` at ~45s, vehicle freed, late
  POST 409. The "never sent a position" path was also confirmed — 3 stale
  leftover trips from prior sessions were swept on the first boot sweep.

## Changelog

| Date | Change | Reason |
|---|---|---|
| 2026-09-19 | Implemented position ingestion, destination-proximity arrival, and the stall watchdog (`STALE` status); Trip gains `last_known_position`/`last_position_at`; `TrackingProperties` added. | MVP-2 per `~/.claude/plans/concurrent-wishing-cocke.md`. |
