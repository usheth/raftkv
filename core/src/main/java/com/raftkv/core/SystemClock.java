package com.raftkv.core;

/**
 * Production Clock implementation backed by System.currentTimeMillis().
 */
public final class SystemClock implements Clock {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
