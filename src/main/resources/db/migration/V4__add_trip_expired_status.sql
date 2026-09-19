-- STALE becomes a soft, recoverable pause (feed silent past a short
-- timeout, vehicle stays ON_TRIP, next position resumes the trip) instead
-- of a terminal status. EXPIRED is the new hard-terminal status for a feed
-- that never resumes past a longer timeout. Design: Shape B, two-tier
-- stall recovery (see plan discussion).

ALTER TABLE trip DROP CONSTRAINT trip_status_valid;
ALTER TABLE trip ADD CONSTRAINT trip_status_valid
    CHECK (status IN ('EN_ROUTE', 'ARRIVED', 'CANCELLED', 'STALE', 'EXPIRED'));

-- A vehicle with a paused (STALE) trip is still not free for a new
-- dispatch — extend the "one active trip per vehicle" index to cover it.
DROP INDEX idx_trip_vehicle_active;
CREATE UNIQUE INDEX idx_trip_vehicle_active ON trip (vehicle_id) WHERE status IN ('EN_ROUTE', 'STALE');
