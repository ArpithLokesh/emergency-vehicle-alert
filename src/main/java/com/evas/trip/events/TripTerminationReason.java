package com.evas.trip.events;

public enum TripTerminationReason {
    CANCELLED,
    ARRIVED,
    EXPIRED,
    /** Not literal termination — the trip is paused (STALE), not ended. Included here rather than a separate event to keep alerting's reset handling in one place. */
    PAUSED
}
