-- MVP-0 schema: the three registry tables. Trip-dependent tables
-- (lamppost_alert, signal_preemption) are created in a later migration once
-- a trip table exists to reference. Design: docs/mvp-0/LLD-data-model.md

-- Idempotent; the postgis/postgis image already enables this, but a plain
-- Postgres would not, and the migration should not depend on the image.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE vehicle (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    call_sign   VARCHAR(32)  NOT NULL UNIQUE,
    type        VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT vehicle_type_valid
        CHECK (type IN ('AMBULANCE', 'POLICE', 'FIRE_ENGINE')),
    CONSTRAINT vehicle_status_valid
        CHECK (status IN ('AVAILABLE', 'ON_TRIP', 'MAINTENANCE', 'RETIRED'))
);

CREATE TABLE lamppost (
    id           UUID                   PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(64)            NOT NULL UNIQUE,
    location     geometry(Point, 4326)  NOT NULL,
    routing_key  VARCHAR(128)           NOT NULL UNIQUE,
    active       BOOLEAN                NOT NULL DEFAULT TRUE
);

-- Spatial queries (ST_DWithin etc.) fall back to a full table scan without
-- a GIST index on the geometry column.
CREATE INDEX idx_lamppost_location ON lamppost USING GIST (location);

CREATE TABLE traffic_signal (
    id        UUID                   PRIMARY KEY DEFAULT gen_random_uuid(),
    code      VARCHAR(64)            NOT NULL UNIQUE,
    location  geometry(Point, 4326)  NOT NULL,
    active    BOOLEAN                NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_traffic_signal_location ON traffic_signal USING GIST (location);
