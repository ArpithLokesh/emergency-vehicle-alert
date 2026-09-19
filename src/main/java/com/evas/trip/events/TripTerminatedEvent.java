package com.evas.trip.events;

import java.util.UUID;

/**
 * Published by {@code TripService} at every point a trip stops being
 * actively tracked, whether for good ({@code CANCELLED}/{@code ARRIVED}/
 * {@code EXPIRED}) or for now ({@code PAUSED}, i.e. STALE — see
 * {@link TripTerminationReason}). One event with a reason discriminator
 * rather than a separate "paused" event, since alerting's reaction is the
 * same shape in every case (revert/clear whatever's currently tracked) and
 * only the audit reason and whether the cache entry survives differ.
 */
public record TripTerminatedEvent(UUID tripId, TripTerminationReason reason) {
}
