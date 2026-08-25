package com.tradinglabs.vidingest.commons;

/**
 * The request is valid but conflicts with the current state of the resource — a duplicate
 * channel, a disabled channel, a guard rail that refuses to run. Maps to HTTP 409.
 *
 * <p>Replaces the blanket {@code IllegalStateException -> 409} mapping the exception handler
 * used to carry: that turned every unexpected illegal state, including ones thrown from
 * Spring and JDBC internals, into a 409 the caller could reasonably retry. Unexpected
 * {@code IllegalStateException}s are now a 500, which is what they are.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
