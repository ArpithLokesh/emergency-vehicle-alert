-- MVP-2: live position tracking + stall detection on trips.
-- Design: ~/.claude/plans/concurrent-wishing-cocke.md

ALTER TABLE trip ADD COLUMN last_known_position geometry(Point, 4326);
ALTER TABLE trip ADD COLUMN last_position_at    TIMESTAMPTZ;

-- Extend the status check to admit STALE (system-detected loss of the
-- position feed — distinct from a dispatcher's CANCELLED). The constraint
-- was created in V1/V2 as trip_status_valid; recreate it with the new value.
ALTER TABLE trip DROP CONSTRAINT trip_status_valid;
ALTER TABLE trip ADD CONSTRAINT trip_status_valid
    CHECK (status IN ('EN_ROUTE', 'ARRIVED', 'CANCELLED', 'STALE'));
