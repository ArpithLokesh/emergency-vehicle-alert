# LLD: Alerting (MVP-3)

**HLD version this was written against:** 1.5.0 (`docs/HLD.md`)
**Status:** Implemented and verified end-to-end against real seeded
Bengaluru signals + a debug RabbitMQ consumer (see Verification).

Alerting is the core product loop: react to every position update by
driving two device command streams over RabbitMQ — traffic-signal
preemption and lamppost broadcast — with a durable audit trail and a
safety-timeout backstop. Single package, `com.evas.alerting`: the
pre-implementation component sketch split this into separate
Alerting/Preemption components, but both device streams are driven by
the same event and share the same window/cache machinery, so the split
never earned its keep.

## Design: windowed cache, not radius-per-tick queries

Each trip's alerting state is a rolling in-memory window of the next
`signal-window-size`/`lamppost-window-size` devices ahead on the route
(default 3/8), one window per device type — not a fresh `ST_DWithin`
radius query every tick. Bounded, predictable per-tick work regardless
of local device density.

**Reactive, not eager**: there's no "populate at trip creation" step.
`TripWindowBuilder.buildFresh` anchors on the trip's current position
(falling back to its origin if none yet) and runs wherever a cache entry
turns out missing — the first position tick on a new trip, a backend
restart, or a resume from `STALE`. One code path for all three cases,
and it meant zero new coupling from `trip` onto `alerting` (window
population never needed to hook into `TripService.create`).

## Schema — `V5__add_alerting_tables.sql`, `V6__add_window_changed_revert_reason.sql`

- `traffic_signal.routing_key VARCHAR(128) NOT NULL UNIQUE` — Gap D:
  `Lamppost` always had one, `TrafficSignal` didn't. Backfilled for
  existing rows as `'signal.' || code`.
- `signal_preemption` — one row per force-green/revert cycle:
  `trip_id`, `signal_id`, `state` (`ACTIVE`/`REVERTED`/`REVERT_FAILED`),
  `revert_reason` (`PASSED`/`TIMEOUT`/`TRIP_TERMINATED`/`TRIP_PAUSED`/
  `WINDOW_CHANGED`, the last added in V6), `force_green_at`,
  `reverted_at`.
  - **Partial unique index**, not a plain one:
    `UNIQUE (trip_id, signal_id) WHERE state = 'ACTIVE'`. Enforces "at
    most one ACTIVE preemption per (trip, signal) at a time" (dedup
    during a live approach) while allowing the multi-cycle audit
    history a pause/resume produces — a plain `UNIQUE(trip_id,
    signal_id)` would block re-preempting the same signal after a
    STALE-triggered revert.
  - A second index, `(force_green_at) WHERE state = 'ACTIVE'`, backs
    the safety-timeout sweep's scan.
- `lamppost_alert` — one row per (trip, lamppost), **fire-once**: the
  first `LAMPPOST_APPROACH` broadcast for that pair, not one row per
  position tick. Plain `UNIQUE(trip_id, lamppost_id)` — no state
  machine, no revert path.

`SignalPreemption`/`LamppostAlert` entities + `SignalPreemptionState`/
`RevertReason` enums map 1:1. `ddl-auto=validate` confirms entity↔schema
match on boot.

## Config — `AlertingProperties` (`alerting.*`)

Same pattern as `TrackingProperties`/`RoutingProperties`: constructor-
bound record, compact-constructor defaults.

| Property | Default | Meaning |
|---|---|---|
| `signal-window-size` | 3 | upcoming signals tracked per trip |
| `lamppost-window-size` | 8 | upcoming lampposts tracked per trip |
| `signal-trigger-distance-meters` | 400 | along-route distance to force-green |
| `revert-margin-meters` | 30 | along-route distance past a device to call it "passed" |
| `deviation-threshold-meters` | 100 | straight-line distance from route to trigger a re-route |
| `route-corridor-meters` | 50 | how far off the route a device can be and still count as "on it" |
| `safety-timeout` | 120s | revert a stuck ACTIVE preemption regardless, after this long |
| `sweep-interval` | 30s | how often the safety-timeout sweep scans |
| `executor.core-pool-size` / `max-pool-size` / `queue-capacity` | 2 / 4 / 100 | bounded async executor sizing |

All hardcoded-but-externalized, pre-MVP-5 — a policy store replaces this
`@ConfigurationProperties` binding later without touching business logic.

## RabbitMQ setup

`spring-boot-starter-amqp`. One durable topic exchange,
`evas.device.commands` (`RabbitTopologyConfig`). Backend only publishes —
never declares/binds a queue; each device owns its own queue, bound by
its own `routing_key`.

`CommandPublisher` — thin `RabbitTemplate.convertAndSend` wrapper.
Publish always happens **inside** the same transaction as the audit-row
write; on failure the whole transaction rolls back (nothing persisted
either), so the next position tick simply retries. Accepted as an MVP
simplification (no outbox/retry queue) because devices carry their own
hardware-level auto-revert (~180s) as an independent second backstop —
standard for real traffic-signal preemption systems.

**Gotchas hit and fixed:**
- `Jackson2JsonMessageConverter` is built against classic Jackson 2.x
  (`com.fasterxml.jackson`) — a different major version from this app's
  own Jackson 3.x REST layer (`tools.jackson`, via
  `spring-boot-starter-jackson`). The two `ObjectMapper` types are
  unrelated; `RabbitTopologyConfig` builds its own Jackson 2.x instance
  (`+ jackson-datatype-jsr310` for `Instant` support) rather than reusing
  the app's bean.
- This Spring AMQP version's `RabbitAdmin` declares exchanges **lazily,
  on first connection** — not eagerly at boot. Confirmed empirically (a
  throwaway smoke-test publish) before relying on it: an idle backend
  that's never published won't show its exchange in the management UI
  until the first message actually goes out.

## Events — `com.evas.trip.events`

- `PositionUpdatedEvent(tripId)` — published by
  `TripService.updatePosition` when the trip is (or resumes to)
  `EN_ROUTE`.
- `TripTerminatedEvent(tripId, reason)`,
  `reason ∈ {CANCELLED, ARRIVED, EXPIRED, PAUSED}` — published by
  `cancel`/the arrival branch of `updatePosition`/`markExpired`/
  `markStale` respectively. One event with a reason discriminator
  rather than a separate "paused" event: Alerting's reaction is the
  same shape in every case (revert/clear whatever's tracked), only the
  audit reason and whether the cache entry survives differ.

Both live in `com.evas.trip.events` (not `com.evas.alerting`) — Trip
owns their lifecycle; Alerting is a consumer. Delivered via
`@Async("alertingExecutor") @TransactionalEventListener(AFTER_COMMIT)`
(`AlertingListener`/`RevertListener`) — the position/termination write is
durable before either listener runs, and a slow or failing evaluation can
never roll back or delay it. `alertingExecutor`
(`AlertingExecutorConfig`) is bounded with `CallerRunsPolicy`: if
alerting can't keep up, position ingestion backpressures rather than an
unbounded queue or a dropped alert.

## `AlertingService.evaluate(tripId)`

Runs `@Transactional`, holding the same pessimistic lock
(`TripRepository#findByIdForUpdate`) `TripService`'s own check-then-write
paths use — for the *whole* method, not just the initial read. This is
what makes the window's check-then-insert-preemption logic safe without
per-row locks on `signal_preemption`: only one `evaluate` (or
`TripService` write) can be in flight for a given trip at a time.

1. Load trip; early-out if not `EN_ROUTE` (race with a terminal/pause
   transition that landed first — that transition's own
   `TripTerminatedEvent` already handles cleanup).
2. **Deviation check**: `TripRepository#distanceToRouteMeters` (native
   `ST_Distance` on `::geography`). Past `deviation-threshold-m`: call
   `RoutingService.getRoute` for a fresh route, save it, rebuild both
   windows via `TripWindowBuilder.buildFresh`, and reconcile — any
   signal/lamppost that dropped out of the new window vs. the old one
   gets `WINDOW_CHANGED` revert / `LAMPPOST_CLEAR`. OSRM failure: log,
   keep the old route this tick, next tick retries.
3. Compute `RouteContext` once — `vehicleFraction`
   (`fractionAlongRoute`) and `routeLengthMeters` — so N device checks
   don't each redundantly recompute the vehicle's own position.
4. **Shift `signalWindow`**: pop any signal now behind the vehicle by
   ≥ `revert-margin-m` (atomic `revertIfActive`, `reason=PASSED`) or no
   longer `active=true` (`reason=WINDOW_CHANGED`); for each pop, one
   `nextSignalAfter` query anchored at the new tail (or the vehicle's own
   position if the window emptied) refills one slot.
5. **Shift `lamppostWindow`**: same shape, publishing `LAMPPOST_CLEAR` on
   pop instead of a stateful revert (no DB state to update — a lamppost
   has no "active" column, just a fire-once audit row).
6. **Fire ACTIVATE**: for each signal still in-window within
   `signal-trigger-distance-m` with no existing `ACTIVE` row — insert
   `SignalPreemption`, publish `SIGNAL_PREEMPTION_ACTIVATE`.
7. **Fire APPROACH**: for each lamppost still in-window ahead of the
   vehicle — publish `LAMPPOST_APPROACH` every tick (first tick for a
   given (trip, lamppost) also inserts the audit row; subsequent ticks
   don't touch the DB).

Distance is **along-route**, not straight-line (`ST_LineLocatePoint`
fraction × real geodesic route length) — and it's **distance**, not ETA:
no speed-estimation state to maintain, and a stopped-in-traffic vehicle
still preempts (which is exactly what a green light is for).

## `AlertingService.handleTripReset(tripId, reason)`

Reacts to `TripTerminatedEvent`. **Signal reverts are DB-authoritative,
not cache-driven** — queries `signal_preemption` directly for anything
still `ACTIVE` for the trip (`findByTripIdAndState`), rather than only
reverting what the in-memory window cache currently holds. Lampposts
still use the cache for `LAMPPOST_CLEAR` (no DB-tracked "currently
broadcasting" state exists for them to query instead).

`reason` maps to a `RevertReason`: `PAUSED → TRIP_PAUSED`;
`CANCELLED`/`ARRIVED`/`EXPIRED → TRIP_TERMINATED`. On `PAUSED`, the cache
entry survives (resume re-activates against the same tracked devices,
no rebuild needed unless the vehicle also deviated during the pause);
otherwise it's invalidated.

Doesn't take the trip lock `evaluate` does — never writes to `trip`,
only to `signal_preemption`, and `revertIfActive` is itself an atomic
conditional update, safe against a concurrent revert from `evaluate`'s
own pass-detection or the safety-timeout sweep landing at the same time.

### Bug found during verification: cache-driven reverts could silently no-op

Originally `handleTripReset` only reverted preemptions found in the
in-memory window cache. On backend restart, `AlertingCacheWarmup` (an
`ApplicationReadyEvent` listener) and the stall watchdog's first
`@Scheduled` sweep have **no defined ordering** relative to each other —
if a trip was already overdue at boot, the sweep could fire and publish
its pause event *before* the warmup rebuilt that trip's cache, leaving
`handleTripReset` looking at an empty cache and silently reverting
nothing. Reproduced deliberately: activate signals, kill the app,
restart with a short stale-timeout override — confirmed the race, then
fixed it by making signal reverts query the database directly. This is
why the design principle above ("signal reverts are DB-authoritative")
exists — it's a lesson learned mid-implementation, not a decision made
up front.

## Safety-timeout sweep — `SignalPreemptionSafetyWatchdog`

Same shape as `TripStaleWatchdog`: `@Scheduled(fixedDelayString =
"${alerting.sweep-interval}")` orchestrator, not itself transactional;
delegates each row to `AlertingService.revertStuckPreemption` (a
separate bean, so its `@Transactional` applies) with a per-row try/catch
so one bad row can't abort the sweep. Finds every `SignalPreemption`
`ACTIVE` past `alerting.safety-timeout` and reverts it
(`reason=TIMEOUT`), regardless of *why* nothing else caught it — a
crashed listener, a bug in `evaluate`'s pass-detection, a vehicle whose
feed dies in a way that skips the normal STALE/EXPIRED lifecycle. Also
logs (informationally — the sweep already handles the actual reverting
on its own cadence) how many preemptions are `ACTIVE` at startup.

## Route math — three native queries on `TripRepository`

`fractionAlongRoute`, `routeLengthMeters`, `distanceToRouteMeters` — pure
geometry math, no `FROM` clause, don't touch the `trip` table at all;
they live here because there's nowhere more natural. Same reasoning as
`GeoPoints.distanceMeters` hand-rolling Haversine instead of JTS's own
(planar) `distance()`: JTS has no geodesic equivalent of
`ST_LineLocatePoint`/`ST_Length`/`ST_Distance`, and PostGIS's
geography-aware versions are the only correct source for real-world
metres.

**Gotcha**: a native-query parameter immediately followed by `::` (e.g.
`:route::geography`) gets misparsed by Hibernate as one parameter
literally named `route::geography` (`QueryParameterException: No
argument for named parameter ':route::geography'`). Fixed by using
explicit `CAST(:route AS geography)` syntax instead of the `::`
shorthand — applies to `TripRepository`'s three queries and
`TrafficSignalRepository`/`LamppostRepository#findNextAlongRoute`
equally.

## Package layout

```
com.evas.alerting/
  AlertingService                — evaluate() + handleTripReset() + revertStuckPreemption()
  AlertingListener                — @Async listener for PositionUpdatedEvent
  RevertListener                  — @Async listener for TripTerminatedEvent
  SignalPreemptionSafetyWatchdog  — @Scheduled belt-and-braces revert + startup log
  TripAlertingCache               — Caffeine wrapper (get/put/invalidate)
  TripAlertingState               — cache value: two device-id windows
  TripWindowBuilder                — buildFresh / nextSignalAfter / nextLamppostAfter
  AlertingCacheWarmup             — rebuilds caches for active trips on ApplicationReadyEvent
  SignalPreemption + repo         — entity + JPA + atomic conditional UPDATE
  SignalPreemptionState           — enum (ACTIVE/REVERTED/REVERT_FAILED)
  LamppostAlert + repo            — entity + JPA (fire-once)
  RevertReason                    — enum (PASSED/TIMEOUT/TRIP_TERMINATED/TRIP_PAUSED/WINDOW_CHANGED)
  SignalPreemptionActivateMessage
  SignalPreemptionRevertMessage
  LamppostApproachMessage
  LamppostClearMessage            — the four RabbitMQ message record types
  CommandPublisher                — RabbitTemplate wrapper
  RabbitTopologyConfig             — exchange bean + Jackson 2.x message converter
  AlertingProperties               — @ConfigurationProperties
  AlertingExecutorConfig           — bounded TaskExecutor bean

com.evas.trip.events/
  PositionUpdatedEvent
  TripTerminatedEvent
  TripTerminationReason
```

## Verification (all passed)

Real app + real seeded Bengaluru signals (405 from OpenStreetMap) + a
debug RabbitMQ queue bound to `evas.device.commands` with routing key
`#`:

- **Happy path**: a real Bengaluru route produced 36 signal preemptions
  across the whole trip, every single one exactly one `ACTIVATE` +
  `REVERT(PASSED)` cycle (confirmed via `psql`, no dupes, none left
  dangling `ACTIVE`); a test lamppost got a decreasing-distance
  `APPROACH` stream (387m → 14m) then one `CLEAR`; window refill
  correctly picked up new signals as old ones popped mid-trip.
- **Terminal fan-out**: `ARRIVED` (the last 3 in-window signals from the
  happy-path trip reverted with `TRIP_TERMINATED` right at arrival) and
  a separate mid-trip `CANCELLED` both worked.
- **Pause/resume**: silence past the soft timeout reverted all `ACTIVE`
  preemptions with `TRIP_PAUSED`; resuming re-activated the *same*
  signals as fresh rows (the partial-unique-on-`ACTIVE` index allowing
  it) — full multi-cycle audit trail confirmed in `psql`.
- **Route deviation**: a manufactured 367m deviation triggered a real
  re-route (227→208 route points) and reverted the 3 signals that fell
  out of the new window with `WINDOW_CHANGED`.
- **RabbitMQ down**: stopping the container mid-trip and POSTing a
  position still returned `200` (position ingestion unaffected); the
  publish failure rolled the whole transaction back cleanly — zero
  orphaned `signal_preemption` rows.
- **Safety-timeout sweep**: activated signals, killed the app, restarted
  with a short `safety-timeout`/`sweep-interval` override — all reverted
  with `reason=TIMEOUT` on the very first sweep. This is also the
  scenario that reproduced the cache-race bug above (the app was
  restarted deliberately while a trip was already overdue, exactly the
  condition that exposed it).
- **Restart cache rebuild**: `AlertingCacheWarmup` correctly rebuilds
  windows for every `EN_ROUTE`/`STALE` trip on boot (confirmed via its
  startup log line matching the actual count of active trips across
  several restarts during this verification pass).

Final production-config boot (120s/300s tracking, 120s/30s alerting)
came up clean with no errors.

## Changelog

| Date | Change | Reason |
|---|---|---|
| 2026-09-20 | Implemented the alerting engine in four staged commits: foundation (data model, RabbitMQ, config), device windows + cache, the event/evaluate loop, safety-timeout sweep. Migrations `V5`/`V6`. | MVP-3 — first true end-to-end demo of the product concept. |
