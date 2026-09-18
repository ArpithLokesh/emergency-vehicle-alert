package com.evas.trip;

public enum TripStatus {
    EN_ROUTE,
    ARRIVED,
    CANCELLED,
    /** System-detected loss of the position feed (stall watchdog), distinct from operator CANCELLED. */
    STALE
}
