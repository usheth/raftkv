package com.raftkv.core;

/**
 * Abstraction over time to allow deterministic testing.
 */
public interface Clock {
    /**
     * Returns the current time in milliseconds.
     */
    long currentTimeMillis();
}
