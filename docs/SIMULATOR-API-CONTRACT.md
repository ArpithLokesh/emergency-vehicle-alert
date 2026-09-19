# Simulator ↔ EVAS Backend — Interface Contract

This document specifies **only the boundary** between the external simulator
and the EVAS backend — the wires in and out, not how either side is built
internally. The simulator author builds to this; the backend is built to
match it. It is the shared source of truth for the integration.

Scope note: some wires are **live today**, some are the **agreed contract
for MVP-2** (I build the backend to this while you build the simulator to
it — we meet in the middle), and one is **MVP-3 (future)**. Each is tagged.

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
   device alerts ◄┼───(RabbitMQ, MVP-3)──────────│
                 └──────────────────────────────┘
   simulator                       backend
   (two roles: vehicle-mover + device-observer)
```

The simulator plays **two independent roles** that never talk to each other
— mirroring reality, where a vehicle and a roadside device only ever talk
to the backend, never to one another:

- **Vehicle-mover** — creates trips, streams positions, reads acks.
- **Device-observer** — receives device alerts over RabbitMQ (MVP-3 only).
  It has nothing to do in MVP-2: there is no nearby-devices HTTP wire (that
  logic is alerting-internal — see W4, removed).

---

## Environment

| | |
|---|---|
| Base URL | `http://localhost:8080` |
| Format | JSON over HTTP |
| Auth | none (authentication is a later milestone) |
| Backend prerequisites | app running; Docker infra up (`evas-postgres`, `evas-rabbitmq`) |
| RabbitMQ (MVP-3) | AMQP `localhost:5672`, management UI `localhost:15672`, user/pass `evas`/`evas` |

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
*internal* logic of the alerting engine (MVP-3), not a public endpoint —
computed server-side to decide whom to alert, never exposed as a read and
never returned to the vehicle. The simulator observes alerting **only** via
the device alerts on RabbitMQ (W7, MVP-3). Do not build a nearby-devices
poller. To watch position/lifecycle progress in MVP-2, use W6.

### W6 — Plain trip read · **LIVE** · `GET /api/trips/{id}`
Full trip incl. `routeGeometry`, `status`, and `lastKnownPosition`.
`GET /api/trips?status=EN_ROUTE` lists active trips. This is how MVP-2
progress is observed: POST positions (W3), watch `lastKnownPosition` move
and `status` go `EN_ROUTE → ARRIVED`, or `→ STALE → EN_ROUTE` (paused then
resumed), or `→ STALE → EXPIRED` (paused, never resumed).

### W7 — Device commands · **MVP-3 (future)** · RabbitMQ (AMQP), **not HTTP**
When alerting exists, device commands are **published to RabbitMQ**, keyed
per device. This is **two distinct message streams**, because the two device
kinds are commanded differently — the fake-device consumers behave
differently per kind:

- **W7a — Traffic-signal preemption** (target: `TRAFFIC_SIGNAL`). A
  **stateful pair**: a *force-green* command for the vehicle's approach
  direction, then later a *revert-to-normal* command once the vehicle has
  passed (or a safety timeout fires). A fake signal must model both — go
  green on the first, restore its prior state on the second. Backed by the
  `Preemption` engine / `SignalPreemption` record.
- **W7b — Emitter broadcast** (target: `LAMPPOST`). A **fire-once**
  *broadcast-make-way* alert the emitter would push to nearby cars over
  LiFi. No revert — it simply expires once the vehicle passes. A fake
  lamppost just logs/emits it. Backed by the `Alerting` engine /
  `LamppostAlert` record.

HTTP tools (Postman) cannot read this wire — use an AMQP consumer, or the
management UI at `localhost:15672` for manual inspection. Exact
exchange/queue/routing-key topology and payload fields for W7a/W7b are not
yet finalized and will be appended here when MVP-3 is designed.

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
  `12.95,77.57 → 13.00,77.65`. Keep origins/destinations inside it so that,
  in MVP-3, the vehicle actually passes real devices to alert.

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

MVP-2 (device-observer idle until MVP-3's RabbitMQ alerts exist):
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

---

## Status legend

| Tag | Meaning |
|---|---|
| **LIVE** | Works against the running backend today (MVP-0 / MVP-1 / MVP-2). |
| **MVP-3 (future)** | Design not finalized; placeholder so the simulator can plan its device-observer role. |

*Last updated: 2026-09-20 (stall detection made two-tier: `STALE` is now
paused/recoverable, `EXPIRED` is the new terminal status — see C3/C4).
Owns the boundary only; backend-internal design lives in `docs/HLD.md`
and `docs/mvp-*/LLD-*.md`.*
