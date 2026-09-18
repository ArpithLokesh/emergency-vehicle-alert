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
- **TrafficSignal preemption field design.** Approach-direction metadata,
  state machine, and command-delivery mechanism are all deferred until the
  Preemption engine is actually built (MVP-3).
- **RabbitMQ exchange/queue/binding topology detail.** MVP-0 only locks in
  that `Lamppost.routingKey` exists; the actual exchange type, binding
  strategy, and consumer-side design belong with the Alerting engine
  (MVP-3).
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

## Changelog

| Date | Change |
|---|---|
| 2026-09-14 | Initial backlog seeded from MVP-0 design session. |
| 2026-09-17 | Added: a real device-import service, deferred in favor of a one-off script (see `scripts/import-osm-traffic-signals.py`). |
