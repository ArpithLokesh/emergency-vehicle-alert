-- MVP-1: the trip table. Design: docs/mvp-1/LLD-trip-and-routing.md

CREATE TABLE trip (
    id              UUID                        PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_id      UUID                        NOT NULL REFERENCES vehicle(id),
    origin          geometry(Point, 4326)       NOT NULL,
    destination     geometry(Point, 4326)       NOT NULL,
    route_geometry  geometry(LineString, 4326)  NOT NULL,
    status          VARCHAR(16)                 NOT NULL,
    created_at      TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,

    CONSTRAINT trip_status_valid
        CHECK (status IN ('EN_ROUTE', 'ARRIVED', 'CANCELLED'))
);

CREATE INDEX idx_trip_vehicle_id ON trip (vehicle_id);

-- "One active trip per vehicle" as a DB-level safety net alongside the
-- application-level check in TripService — catches a race between two
-- near-simultaneous requests that the app check alone could miss.
CREATE UNIQUE INDEX idx_trip_vehicle_active ON trip (vehicle_id) WHERE status = 'EN_ROUTE';
