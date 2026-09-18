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
   nearby ETAs ◄──┼─────────────                  │
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
- **Device-observer** — reads nearby-device ETAs (HTTP) and, in MVP-3,
  receives device alerts (RabbitMQ).

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
| `TripStatus` | `EN_ROUTE` \| `ARRIVED` \| `CANCELLED` |
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

### W3 — Send a position update · **MVP-2 (contract)** · `POST /api/trips/{id}/position`
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
←  409  trip not EN_ROUTE (already ARRIVED/CANCELLED) — stop sending for it
←  400  lat/lng out of range
```

### W5 — Cancel a trip · **LIVE** · `POST /api/trips/{id}/cancel`
For teardown / abandoning a run. Frees the vehicle back to `AVAILABLE`.
```
←  200  the trip, now "status":"CANCELLED"
←  409  trip not EN_ROUTE (already terminal)
```

---

## Wires INTO the simulator (responses / feeds it reads)

### W4 — Nearby-device ETAs · **MVP-2 (contract)** · `GET /api/trips/{id}/nearby-devices`
The device-observer's read. Returns the devices the vehicle is *approaching*
(ahead of it, within a radius the backend configures, ~2 km), soonest first.
This is where "shrinking ETAs" is observed. Independent of W3 — reading it
never moves the vehicle, and it is never returned to the vehicle.
```
←  200  [
          {"deviceId":"<uuid>","type":"TRAFFIC_SIGNAL","code":"TS-OSM-26529522",
           "location":{"lat":12.9746,"lng":77.6201},
           "distanceMeters":840.5,"etaSeconds":72}
        ]                                             // [] if no position yet, or nothing ahead in range
←  404  unknown trip id
```
`etaSeconds` is an approximate estimate (constant-speed assumption) — it
demonstrates the mechanism, not precise timing.

### W6 — Plain trip read · **LIVE (core) / +position in MVP-2** · `GET /api/trips/{id}`
Full trip incl. `routeGeometry`, `status`, and (from MVP-2)
`lastKnownPosition`. `GET /api/trips?status=EN_ROUTE` lists active trips.

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
- **C3 — terminal trips reject positions.** Once `ARRIVED` or `CANCELLED`,
  W3 returns `409`. The mover should treat any `409`/`404` on W3 as
  "stop tracking this trip."
- **C4 — positions should trace the route.** Nothing rejects an off-route
  position, but `nearby-devices` (W4) only returns devices *ahead along the
  route within range*, so positions that wander off the returned
  `routeGeometry` will show empty/erratic results.
- **C5 — realistic test area.** The seeded real devices (405 traffic signals
  from OpenStreetMap) cover central Bengaluru, bounding box
  `12.95,77.57 → 13.00,77.65`. Keep origins/destinations inside it so W4
  returns real devices.

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

```
vehicle-mover                         backend                  device-observer
     │  W1 POST /api/vehicles ──────────►│                            │
     │◄──────────── 201 {id: V} ─────────│                            │
     │  W2 POST /api/trips (V, o, d) ────►│                            │
     │◄─── 201 {id: T, routeGeometry} ───│                            │
     │                                    │   W4 GET .../T/nearby-devices ►│
     │  W3 POST .../T/position (p1) ─────►│◄──── 200 [ETAs...] ────────────│
     │◄── 200 {status: EN_ROUTE} ────────│                            │
     │  ... repeat W3 along the route ...  (observer polls W4 in parallel) │
     │  W3 POST .../T/position (p_last) ─►│                            │
     │◄── 200 {status: ARRIVED} ─────────│   (vehicle auto-freed)     │
     │  stop.                              │                            │
```

---

## Status legend

| Tag | Meaning |
|---|---|
| **LIVE** | Works against the running backend today (MVP-0 / MVP-1). |
| **MVP-2 (contract)** | Not built yet; this doc is the agreed shape. Backend and simulator build to it in parallel. |
| **MVP-3 (future)** | Design not finalized; placeholder so the simulator can plan its device-observer role. |

*Last updated: 2026-09-18. Owns the boundary only; backend-internal design
lives in `docs/HLD.md` and `docs/mvp-*/LLD-*.md`.*
