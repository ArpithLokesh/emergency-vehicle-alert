-- Alerting foundation: signal preemption + lamppost broadcast audit trail,
-- and the RabbitMQ routing key TrafficSignal was missing (Lamppost already
-- has one). Design: docs (alerting section) / plan discussion.

ALTER TABLE traffic_signal ADD COLUMN routing_key VARCHAR(128);
UPDATE traffic_signal SET routing_key = 'signal.' || code;
ALTER TABLE traffic_signal ALTER COLUMN routing_key SET NOT NULL;
ALTER TABLE traffic_signal ADD CONSTRAINT traffic_signal_routing_key_unique UNIQUE (routing_key);

CREATE TABLE signal_preemption (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id        UUID        NOT NULL REFERENCES trip(id),
    signal_id      UUID        NOT NULL REFERENCES traffic_signal(id),
    state          VARCHAR(16) NOT NULL,
    revert_reason  VARCHAR(32),
    force_green_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reverted_at    TIMESTAMPTZ,

    CONSTRAINT signal_preemption_state_valid
        CHECK (state IN ('ACTIVE', 'REVERTED', 'REVERT_FAILED')),
    CONSTRAINT signal_preemption_revert_reason_valid
        CHECK (revert_reason IS NULL OR revert_reason IN ('PASSED', 'TIMEOUT', 'TRIP_TERMINATED', 'TRIP_PAUSED'))
);

-- Dedup during a live approach only: at most one ACTIVE preemption per
-- (trip, signal) at a time. Multiple historical rows for the same
-- (trip, signal) pair are legitimate — a trip that pauses (STALE) and
-- resumes can re-preempt the same signal, and each cycle is its own row.
CREATE UNIQUE INDEX signal_preemption_trip_signal_active
    ON signal_preemption (trip_id, signal_id) WHERE state = 'ACTIVE';

-- Fast lookup for the safety-timeout sweep.
CREATE INDEX idx_signal_preemption_active_since
    ON signal_preemption (force_green_at) WHERE state = 'ACTIVE';

CREATE TABLE lamppost_alert (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id     UUID        NOT NULL REFERENCES trip(id),
    lamppost_id UUID        NOT NULL REFERENCES lamppost(id),
    fired_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Fire-once semantics: one audit row per (trip, lamppost) for the whole
    -- trip, even across a pause/resume — the lamppost's first approach
    -- broadcast is what's recorded, not every position tick.
    CONSTRAINT lamppost_alert_trip_lamppost_unique UNIQUE (trip_id, lamppost_id)
);
