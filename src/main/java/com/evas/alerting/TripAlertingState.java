package com.evas.alerting;

import java.util.List;
import java.util.UUID;

/**
 * In-memory alerting state for one trip: the rolling window of upcoming
 * devices ahead of the vehicle, ordered nearest-first. Deliberately just
 * device ids — the alerting engine re-fetches the entity when it actually
 * needs to act on one, so a device's {@code active} flag or routing key is
 * always read fresh rather than trusted from whenever it entered the
 * window. Held only in {@link TripAlertingCache}; the database has no
 * matching table since this is derived, rebuildable state.
 */
public record TripAlertingState(
        List<UUID> signalWindow,
        List<UUID> lamppostWindow
) {
    public TripAlertingState {
        signalWindow = List.copyOf(signalWindow);
        lamppostWindow = List.copyOf(lamppostWindow);
    }
}
