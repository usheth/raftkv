package com.raftkv.raft;

/**
 * AppendEntries RPC response.
 */
public final class AppendEntriesResponse {
    private final long term;
    private final boolean success;
    /** Hint for fast log backtracking on failure (0 means no hint). */
    private final long conflictIndex;

    public AppendEntriesResponse(long term, boolean success) {
        this(term, success, 0);
    }

    public AppendEntriesResponse(long term, boolean success, long conflictIndex) {
        this.term = term;
        this.success = success;
        this.conflictIndex = conflictIndex;
    }

    public long getTerm() { return term; }
    public boolean isSuccess() { return success; }
    public long getConflictIndex() { return conflictIndex; }
}
