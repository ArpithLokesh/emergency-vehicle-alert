package com.evas.trip.events;

import java.util.UUID;

/**
 * Published by {@code TripService.updatePosition} after a position commits
 * for a trip that's still (or newly resumed to) {@code EN_ROUTE} — never
 * for one that just went terminal, which publishes {@link TripTerminatedEvent}
 * instead. Only ever consumed asynchronously, after the transaction commits
 * (see {@code AlertingListener}), so a slow or failing listener can never
 * affect the position write itself.
 */
public record PositionUpdatedEvent(UUID tripId) {
}
