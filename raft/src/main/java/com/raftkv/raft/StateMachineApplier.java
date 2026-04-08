package com.raftkv.raft;

import com.raftkv.core.LogEntry;

/**
 * Called when a log entry is committed and should be applied to the state machine.
 * The real implementation lives in the {@code store} or {@code server} module.
 */
public interface StateMachineApplier {
    /**
     * Apply a committed log entry to the state machine.
     *
     * @param entry the committed entry
     */
    void apply(LogEntry entry);
}
