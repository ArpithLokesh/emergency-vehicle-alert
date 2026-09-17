package com.evas.common;

/**
 * Thrown for a business-rule conflict — the request is well-formed but
 * clashes with current state (e.g. "vehicle is not AVAILABLE", "vehicle
 * already has an active trip"). Mapped to 409 by {@link GlobalExceptionHandler}.
 * Distinct from a DB unique-constraint violation, which is a different kind
 * of 409 handled separately.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
