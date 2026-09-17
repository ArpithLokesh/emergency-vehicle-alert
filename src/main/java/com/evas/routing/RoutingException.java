package com.evas.routing;

/** Thrown when OSRM can't be reached or returns no usable route. Mapped to 502 by GlobalExceptionHandler. */
public class RoutingException extends RuntimeException {

    public RoutingException(String message) {
        super(message);
    }

    public RoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
