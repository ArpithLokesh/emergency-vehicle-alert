# Future Upgrades

A tracked backlog of deferred-but-planned ideas — things we intend to
revisit, as opposed to `ROADMAP.md`'s "truly out of scope" list, which is
permanently excluded from this project's vision. Add to this whenever a
"later, not now" idea comes up during design so it doesn't get lost.

- **Migrate `LamppostAlert` off Postgres to a dedicated logging service.**
  It's kept as a minimal DB row for now (idempotency + basic audit), but the
  long-term intent is a proper logging/observability pipeline instead of a
  database table, once one exists.
- **Swap Caffeine for Redis.** Caching starts in-memory (Caffeine) since the
  deployment model is single-instance/no-HA; swappable later by changing
  only the `CacheManager` bean — business code using `@Cacheable` doesn't
  change.
- **TrafficSignal approach-direction + on-device state machine.** MVP-3
  added `routingKey` and the command-delivery mechanism (RabbitMQ,
  edge-triggered ACTIVATE/REVERT) — resolved. Still deferred: approach-
  direction metadata (which physical approach to force green for, at a
  multi-approach junction) and any signal-side state machine beyond
  "green or not" — not needed for the current design, revisit if a real
  junction integration ever requires it.
- **Lamppost bulk import.** `TrafficSignal` has a real importer
  (`scripts/import-osm-traffic-signals.py`, replays OSM data through the
  live endpoint); `Lamppost` has no equivalent — OSM doesn't tag street
  lighting with LiFi-broadcaster metadata the way it tags traffic
  signals, so there's no obvious public data source to replay. Only a
  handful of lampposts exist in the dev DB, created by hand for testing.
  Revisit if/when a real lamppost inventory (or a synthetic generator
  along real streets) becomes available.
- **Multi-authority data model readiness.** Deployment model is single
  traffic authority per instance for now; the policy/config model (MVP-5)
  should be designed so a future authority-scoping concept could be added
  without a full rewrite, even though it's not built now.
- **A real device-import service.** Real traffic signal data is currently
  loaded via `scripts/import-osm-traffic-signals.py`, a one-off script that
  replays an Overpass/OSM export through `POST /api/traffic-signals` — no
  new application code, but also not reusable as a product feature. If
  bulk device import ever becomes an actual Admin-facing capability (e.g.
  "upload a GIS export"), build a proper importer: a parser + idempotent
  upsert, not a script. Chose the script now because the need was
  speculative; revisit if it stops being speculative.
- **RabbitMQ outbox pattern for device commands.** MVP-3 publishes inside
  the same DB transaction as the audit-row write; on failure the whole
  transaction rolls back and the next position tick simply retries.
  Accepted as an MVP simplification because devices carry their own
  hardware-level auto-revert (~180s) as an independent backstop on top
  of the backend's own safety-timeout sweep — a signal is never solely
  dependent on this backend successfully delivering a message. Revisit
  under MVP-6 hardening if delivery guarantees ever need to be stronger
  than "retried on the next position tick, with a device-side safety
  net."

## Changelog

| Date | Change |
|---|---|
| 2026-09-14 | Initial backlog seeded from MVP-0 design session. |
| 2026-09-17 | Added: a real device-import service, deferred in favor of a one-off script (see `scripts/import-osm-traffic-signals.py`). |
| 2026-09-20 | MVP-3 shipped: resolved the TrafficSignal-preemption and RabbitMQ-topology items (narrowed to what's still actually deferred). Added: lamppost bulk import (no OSM data source exists the way it does for signals), RabbitMQ outbox pattern (currently log-and-continue, backed by device-side auto-revert). |
