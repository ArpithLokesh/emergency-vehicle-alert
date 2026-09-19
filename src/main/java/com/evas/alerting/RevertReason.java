package com.evas.alerting;

/** Why a signal preemption was reverted — carried through to the audit row and the RabbitMQ revert message. */
public enum RevertReason {
    /** The vehicle's position crossed past the signal along the route. */
    PASSED,
    /** The safety-timeout sweep reverted a preemption stuck ACTIVE too long. */
    TIMEOUT,
    /** The trip ended (CANCELLED, ARRIVED, or EXPIRED). */
    TRIP_TERMINATED,
    /** The trip's feed went silent (STALE) — reverted safety-first; may re-preempt on resume. */
    TRIP_PAUSED
}
