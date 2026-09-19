package com.evas.alerting;

import java.time.Instant;
import java.util.UUID;

/** Single-shot stop-broadcasting command, sent when a lamppost exits the trip's window. See docs/SIMULATOR-API-CONTRACT.md W7b. */
public record LamppostClearMessage(
        String type,
        UUID tripId,
        UUID lamppostId,
        Instant issuedAt
) {
    public static LamppostClearMessage of(UUID tripId, UUID lamppostId) {
        return new LamppostClearMessage("LAMPPOST_CLEAR", tripId, lamppostId, Instant.now());
    }
}
