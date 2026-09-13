Flyway migrations live here, named `V<version>__<description>.sql`
(double underscore). Flyway runs them in version order on startup and
records each one in `flyway_schema_history` — it never re-runs a migration
that's already applied.

Rules that matter:

- **Never edit a migration that has already run** against a database you
  care about. Flyway stores a checksum of each applied file and will refuse
  to start if one has changed. Write a new `V<n+1>__...sql` instead.
- To wipe and start over in local dev: `docker rm -f evas-postgres`, delete
  the `evas-pgdata` volume, and re-run the container — then the next boot
  replays every migration from V1.

Schema design lives in `docs/mvp-0/LLD-data-model.md`.
