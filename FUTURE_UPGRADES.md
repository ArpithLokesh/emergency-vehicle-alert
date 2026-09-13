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

## Changelog

| Date | Change |
|---|---|
| 2026-09-14 | Initial backlog seeded from MVP-0 design session. |
