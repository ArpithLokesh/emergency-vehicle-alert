package com.evas.trip;

public enum TripStatus {
    EN_ROUTE,
    ARRIVED,
    CANCELLED,
    /**
     * Feed silent past the soft timeout. NOT terminal — a paused, recoverable
     * state: the vehicle stays ON_TRIP, and the next position update resumes
     * the trip back to EN_ROUTE. Real GPS feeds drop for real reasons
     * (tunnels, underpasses, dense-urban canyons) that are not "the vehicle
     * gave up" — this state exists so a 60-120s blackout doesn't strand an
     * active emergency response. See {@link com.evas.trip.TripStaleWatchdog}.
     */
    STALE,
    /**
     * Feed silent past the hard timeout with no resume — terminal, frees the
     * vehicle. The eventual outcome for a trip that entered STALE and never
     * came back (real device failure, crash, or the vehicle's run genuinely
     * ended without a cancel/arrival).
     */
    EXPIRED
}
