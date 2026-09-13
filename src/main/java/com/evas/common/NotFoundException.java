package com.evas.common;

/** Thrown when a lookup by id finds nothing. Mapped to 404 by {@link GlobalExceptionHandler}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
