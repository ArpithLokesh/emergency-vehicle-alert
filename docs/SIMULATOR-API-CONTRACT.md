# Simulator ↔ EVAS Backend — Interface Contract

This document specifies **only the boundary** between the external simulator
and the EVAS backend — the wires in and out, not how either side is built
internally. The simulator author builds to this; the backend is built to
match it. It is the shared source of truth for the integration.

Scope note: every wire below is **LIVE** — MVP-3 (alerting) is built and
verified, including the RabbitMQ device-command streams. See the status
legend at the bottom.

```
                 ┌──────────────────────────────┐
   create vehicle │                              │
   create trip    │                              │
   send position ─┼──────────────►  EVAS         │
                  │                 backend       │
   (route out) ◄──┼─────────────    (the "box")   │
   (ack out)   ◄──┼─────────────                  │
   (trip read) ◄──┼─────────────                  │
                  │                              │
   device alerts ◄┼───(RabbitMQ, live)───────────│
                 └──────────────────────────────┘
   simulator                       backend
   (two roles: vehicle-mover + device-observer)
```

The simulator plays **two independent roles** that never talk to each other
— mirroring reality, where a vehicle and a roadside device only ever talk
to the backend, never to one another:

- **Vehicle-mover** — creates trips, streams positions, reads acks.
- **Device-observer** — receives device alerts over RabbitMQ (W7, live).
  There is no nearby-devices HTTP wire — that logic is alerting-internal
  (see W4, removed); the device-observer's only signal is what arrives on
  its bound queue.

---

## Environment

| | |
|---|---|
| Base URL | `http://localhost:8080` |
| Format | JSON over HTTP |
| Auth | none (authentication is a later milestone) |
| Backend prerequisites | app running; Docker infra up (`evas-postgres`, `evas-rabbitmq`) |
| RabbitMQ | AMQP `localhost:5672`, management UI `localhost:15672`, user/pass `evas`/`evas` |

---

## Shared data types

| Type | Shape / values |
|---|---|
| Geo point | `{"lat": <number -90..90>, "lng": <number -180..180>}` — plain lat/lng, **not** GeoJSON |
| `VehicleType` | `AMBULANCE` \| `POLICE` \| `FIRE_ENGINE` |
| `VehicleStatus` | `AVAILABLE` \| `ON_TRIP` \| `MAINTENANCE` \| `RETIRED` |
| `TripStatus` | `EN_ROUTE` \| `ARRIVED` \| `CANCELLED` \| `STALE` (feed silent, paused — recoverable, see C3/C4) \| `EXPIRED` (feed silent for too long — terminal, see C4) |
| Device `type` | `TRAFFIC_SIGNAL` \| `LAMPPOST` |
| Error body | RFC 7807: `{"title","status","detail","instance"}` |

---

## Wires OUT of the simulator (requests it sends)

### W1 — Create a vehicle · **LIVE** · `POST /api/vehicles`
One vehicle is needed per concurrent trip (see constraint C1).
```
→  {"callSign": "SIM-001", "type": "AMBULANCE"}
←  201  {"id":"<uuid>","callSign":"SIM-001","type":"AMBULANCE","status":"AVAILABLE","createdAt":"<iso8601>"}
←  409  call sign already exists
←  400  missing/invalid field
```

### W2 — Create a trip · **LIVE** · `POST /api/trips`
The response carries `routeGeometry` — the road-snapped polyline (typically
100–500 `{lat,lng}` points) the vehicle-mover traces. That is the *input*
the mover consumes; how it turns the polyline into a stream of positions is
internal to the simulator and not specified here.
```
→  {"vehicleId":"<uuid>",
    "origin":{"lat":12.9716,"lng":77.5946},
    "destination":{"lat":12.9784,"lng":77.6408}}
←  201  {"id":"<tripId>","vehicleId":"<uuid>",
         "origin":{...},"destination":{...},
         "routeGeometry":[{"lat":..,"lng":..}, ...],
         "status":"EN_ROUTE","createdAt":"<iso8601>","completedAt":null}
←  409  vehicle not AVAILABLE, or already has an active trip (see C1)
←  502  OSRM unreachable — transient, retry the same request
←  400  missing/invalid field, or lat/lng out of range
```

### W3 — Send a position update · **LIVE** · `POST /api/trips/{id}/position`
The vehicle-mover's per-position call. The vehicle reports where it is and
receives a **receipt of its own trip state only** — no device information
flows back to the vehicle.
```
→  {"lat": 12.9750, "lng": 77.6100}
←  200  {"tripId":"<tripId>",
         "status":"EN_ROUTE",                        // flips to "ARRIVED" within ~30 m of destination
         "lastKnownPosition":{"lat":12.9750,"lng":77.6100},
         "completedAt":null}                         // set when ARRIVED
←  404  unknown trip id
←  409  trip not accepting positions (already ARRIVED/CANCELLED/EXPIRED) — stop sending for it
←  400  lat/lng out of range
```
**Resume behavior:** a trip that went `STALE` (see C4) is **not** terminal
— posting a position to it succeeds (`200`) and the response's `status`
flips straight back to `"EN_ROUTE"`. There is no separate "resume" call;
if your mover just keeps sending on its normal cadence after a gap, the
trip picks itself back up transparently. Only a trip that reached
`ARRIVED`/`CANCELLED`/`EXPIRED` rejects further positions with `409`.

### W5 — Cancel a trip · **LIVE** · `POST /api/trips/{id}/cancel`
For teardown / abandoning a run. Frees the vehicle back to `AVAILABLE`.
Works on an `EN_ROUTE` trip **or** a currently-`STALE` (paused) one — you
don't need to wait for a paused trip to resume or expire before cancelling it.
```
←  200  the trip, now "status":"CANCELLED"
←  409  trip already terminal (ARRIVED/CANCELLED/EXPIRED)
```

---

## Wires INTO the simulator (responses / feeds it reads)

### ~~W4 — Nearby-device ETAs~~ · **REMOVED — there is no such wire**
Earlier drafts had a `GET /api/trips/{id}/nearby-devices` read for observing
"shrinking ETAs." It was cut: "which devices is the vehicle approaching" is
*internal* logic of the alerting engine, not a public endpoint — computed
server-side to decide whom to alert, never exposed as a read and never
returned to the vehicle. The simulator observes alerting **only** via the
device alerts on RabbitMQ (W7). Do not build a nearby-devices poller. To
watch position/lifecycle progress, use W6.

### W6 — Plain trip read · **LIVE** · `GET /api/trips/{id}`
Full trip incl. `routeGeometry`, `status`, and `lastKnownPosition`.
`GET /api/trips?status=EN_ROUTE` lists active trips. This is how trip
progress is observed: POST positions (W3), watch `lastKnownPosition` move
and `status` go `EN_ROUTE → ARRIVED`, or `→ STALE → EN_ROUTE` (paused then
resumed), or `→ STALE → EXPIRED` (paused, never resumed).

### W7 — Device commands · **LIVE** · RabbitMQ (AMQP), **not HTTP**
Device commands are published to one durable **topic exchange**,
`evas.device.commands`. The backend only ever publishes here — it never
declares or binds a queue. To observe traffic, the device-observer (or a
debug consumer) declares its own queue and binds it to this exchange with
whatever routing-key pattern it wants (`#` catches everything).

**Routing key** = the target device's own `routing_key`, exactly as set
when the device was registered — `POST /api/lampposts` always required a
`routingKey` field, and `POST /api/traffic-signals` now does too. A
sensible convention if you're seeding your own test devices:
`signal.<code>` / `lamppost.<code>`, but the backend treats it as an
opaque string — bind however you like.

This is **two distinct message streams**, because the two device kinds are
commanded differently — the fake-device consumers behave differently per
kind:

- **W7a — Traffic-signal preemption** (target: `TRAFFIC_SIGNAL`). A
  **stateful, edge-triggered pair**: one `SIGNAL_PREEMPTION_ACTIVATE` when
  the vehicle enters trigger distance, later exactly one
  `SIGNAL_PREEMPTION_REVERT` — on a normal pass, on the trip
  ending/pausing, or from the backend's own safety-timeout sweep if
  nothing else reverted it within ~120s. A fake signal must model both —
  go green on ACTIVATE, restore prior state on REVERT. Idempotent per
  (trip, signal) while active — never two ACTIVATEs in a row without a
  REVERT between them — but a **paused-then-resumed** trip can
  legitimately re-preempt the same signal (a fresh ACTIVATE after an
  earlier REVERT), so don't assume "one activate ever" for a given
  (trip, signal) pair.
  ```json
  ACTIVATE  {"type":"SIGNAL_PREEMPTION_ACTIVATE","tripId":"<uuid>","signalId":"<uuid>",
             "distanceM":184.3,"vehicleType":"AMBULANCE","issuedAt":"<epoch-seconds-decimal>"}
  REVERT    {"type":"SIGNAL_PREEMPTION_REVERT","tripId":"<uuid>","signalId":"<uuid>",
             "reason":"PASSED","issuedAt":"<epoch-seconds-decimal>"}
  ```
  `reason` is one of `PASSED` \| `TIMEOUT` \| `TRIP_TERMINATED` \|
  `TRIP_PAUSED` \| `WINDOW_CHANGED` (the last is a route deviation
  dropping the signal from tracking, or the signal being deactivated
  mid-approach) — informational only, a fake signal doesn't need to branch
  on it, just revert.
- **W7b — Lamppost broadcast** (target: `LAMPPOST`). **Level-triggered,
  not fire-once**: `LAMPPOST_APPROACH` is sent on **every** position tick
  while the lamppost is in the vehicle's tracking window, carrying the
  current along-route distance (not ETA — no speed model). A fake
  lamppost re-broadcasts this to nearby cars over LiFi each time it
  arrives; expect a steady stream of decreasing `distanceM` values, not a
  single message. Exactly one `LAMPPOST_CLEAR` follows once the vehicle
  passes, the trip ends/pauses, or the lamppost is deactivated.
  ```json
  APPROACH  {"type":"LAMPPOST_APPROACH","tripId":"<uuid>","lamppostId":"<uuid>",
             "distanceM":92.5,"vehicleType":"AMBULANCE","issuedAt":"<epoch-seconds-decimal>"}
  CLEAR     {"type":"LAMPPOST_CLEAR","tripId":"<uuid>","lamppostId":"<uuid>",
             "issuedAt":"<epoch-seconds-decimal>"}
  ```

All four message types carry a `type` field as the discriminator (also set
as the AMQP header `__TypeId__`, but branch on the JSON body's `type` field
— it's the stable contract, the header is a Spring artifact). `issuedAt` is
a server-clock timestamp, decimal epoch seconds (Jackson's default
`Instant` serialization) — not an ISO-8601 string like the HTTP wires use.

HTTP tools (Postman) cannot read this wire — use an AMQP consumer, or the
management UI at `localhost:15672` (Queues → your queue → **Get messages**)
for manual inspection.

---

## Protocol constraints (backend-enforced — govern the wires)

- **C1 — one active trip per vehicle.** A vehicle with an `EN_ROUTE` trip
  is `ON_TRIP` and cannot start another (`409`). N concurrent trips require
  N distinct `AVAILABLE` vehicles. This is enforced at the database, not
  just checked politely.
- **C2 — arrival handshake.** The backend owns the arrival decision (vehicle
  within ~30 m of destination). The simulator learns of it via `status:
  "ARRIVED"` in the W3 ack, and must then stop sending positions for that
  trip. The vehicle is auto-freed to `AVAILABLE` on arrival.
- **C3 — terminal trips reject positions; `STALE` does not.** Once
  `ARRIVED`, `CANCELLED`, or `EXPIRED`, W3 returns `409` — the mover should
  treat any `409`/`404` on W3 as "stop tracking this trip." `STALE` is
  **not** terminal: W3 still returns `200` and resumes the trip (see W3's
  "Resume behavior" above).
- **C4 — the stall watchdog is two-tier.** If the backend receives no
  position for a trip within its soft timeout (~120s, configurable), it
  pauses the trip to `STALE` — the vehicle stays assigned (`ON_TRIP`), and
  it is **not** freed for redispatch. Keep sending on your chosen cadence
  (well under the timeout) while a trip is live; a brief gap (a real
  tunnel, an underpass) recovers automatically the moment you resume
  sending (C3). Only if the feed stays silent past the longer hard
  timeout (~300s, configurable) does the backend give up: the trip flips
  to `EXPIRED` (terminal) and the vehicle is freed. To resume after that
  point, start a new trip.
- **C5 — realistic test area.** The seeded real devices (405 traffic signals
  from OpenStreetMap) cover central Bengaluru, bounding box
  `12.95,77.57 → 13.00,77.65`. Keep origins/destinations inside it so the
  vehicle actually passes real devices to alert — verified end-to-end
  against this exact data (a route through it produced dozens of real
  ACTIVATE/REVERT cycles). Lampposts are not seeded in bulk the way
  signals are (no import script exists yet) — register a few of your own
  near your test route via `POST /api/lampposts` if you want to exercise
  W7b.
- **C6 — device windows are bounded, not exhaustive.** The backend tracks
  only the next few devices ahead of the vehicle per type (a small rolling
  window, not every device within some radius) — so don't expect a
  broadcast the instant a device comes into physical range if the vehicle
  is still passing several closer devices of the same type first. This
  only matters in unusually device-dense areas; anywhere realistic, it's
  unnoticeable.

---

## Error format (all endpoints)

Failures are RFC 7807 problem details:
```json
{ "title": "Conflict", "status": 409,
  "detail": "Vehicle <id> is not AVAILABLE (current status: ON_TRIP)",
  "instance": "/api/trips" }
```
The simulator should branch on `status` (the HTTP code), and can surface
`detail` for logging.

---

## End-to-end sequence (one trip)

Vehicle-mover side:
```
vehicle-mover                         backend
     │  W1 POST /api/vehicles ──────────►│
     │◄──────────── 201 {id: V} ─────────│
     │  W2 POST /api/trips (V, o, d) ────►│
     │◄─── 201 {id: T, routeGeometry} ───│
     │  W3 POST .../T/position (p1) ─────►│
     │◄── 200 {status: EN_ROUTE} ────────│   (keep sending under the stall timeout)
     │  ... repeat W3 along the route ...  │
     │  W3 POST .../T/position (p_last) ─►│
     │◄── 200 {status: ARRIVED} ─────────│   (vehicle auto-freed)
     │  stop.                              │
```
If the mover stops before arriving, the backend's stall watchdog pauses the
trip to `STALE` after the soft timeout (C4) — the vehicle stays assigned.
If positions resume before the hard timeout, the trip picks back up
automatically:
```
     │  ... feed goes silent for a while ...             │
     │  (backend pauses the trip to STALE at soft timeout)│
     │  W3 POST .../T/position (resumed) ────────────────►│
     │◄── 200 {status: EN_ROUTE} ─────────────────────────│   (back to normal)
```
If the feed never resumes past the hard timeout, the backend gives up:
the trip flips to `EXPIRED` and the vehicle is freed for redispatch.

Device-observer side, watching the **same trip** concurrently over W7 —
each device only sees traffic once the vehicle enters its trigger/tracking
distance, driven purely by the position stream above, never polled:
```
device-observer (bound to its own routing key)      backend
     │                                                    │
     │  ... vehicle approaches, several W3 ticks pass ...  │
     │◄──────────── SIGNAL_PREEMPTION_ACTIVATE ───────────│   (signal enters trigger distance)
     │◄──────────── LAMPPOST_APPROACH (dist=310m) ────────│   (lamppost enters window)
     │◄──────────── LAMPPOST_APPROACH (dist=180m) ────────│   (repeats every tick, decreasing)
     │◄──────────── LAMPPOST_APPROACH (dist=40m) ─────────│
     │◄──────────── LAMPPOST_CLEAR ───────────────────────│   (vehicle passed the lamppost)
     │◄──────────── SIGNAL_PREEMPTION_REVERT (PASSED) ────│   (vehicle passed the signal)
```
If the trip is cancelled, arrives, or pauses (STALE) while a signal is
still `ACTIVE`, a `SIGNAL_PREEMPTION_REVERT` fires immediately regardless
of position (`reason` reflects why) — a fake signal should never be left
green because the trip simply stopped sending positions.

---

## Status legend

| Tag | Meaning |
|---|---|
| **LIVE** | Works against the running backend today. |

Every wire in this document is now live — MVP-0 through MVP-3 are all
built and verified end-to-end, including the RabbitMQ device-command
streams (W7).

*Last updated: 2026-09-20 (MVP-3 shipped: W7a/W7b finalized with real
message shapes and the `evas.device.commands` topology; added C6 on
bounded device windows). Owns the boundary only; backend-internal design
lives in `docs/HLD.md` and `docs/mvp-*/LLD-*.md`.*
