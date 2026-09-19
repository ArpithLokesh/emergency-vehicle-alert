package com.evas.alerting;

public enum SignalPreemptionState {
    ACTIVE,
    REVERTED,
    /** Revert command failed to publish; the row didn't roll back, but the device may not have heard. */
    REVERT_FAILED
}
