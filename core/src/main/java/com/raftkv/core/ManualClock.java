package com.raftkv.core;

/**
 * Clock implementation for tests. Time only advances when {@link #advance(long)} is called.
 */
public final class ManualClock implements Clock {
    private long timeMillis;

    public ManualClock(long initialTimeMillis) {
        this.timeMillis = initialTimeMillis;
    }

    public ManualClock() {
        this(0L);
    }

    @Override
    public long currentTimeMillis() {
        return timeMillis;
    }

    /**
     * Advances the clock by {@code ms} milliseconds.
     */
    public void advance(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("Cannot advance clock by a negative amount");
        }
        timeMillis += ms;
    }

    /**
     * Sets the clock to an absolute time.
     */
    public void setTime(long millis) {
        this.timeMillis = millis;
    }
}
