package com.raftkv.raft;

import com.raftkv.core.LogEntry;
import com.raftkv.core.NodeId;

import java.util.List;

/**
 * AppendEntries RPC arguments (also used as heartbeat when entries is empty).
 */
public final class AppendEntriesRequest {
    private final long term;
    private final NodeId leaderId;
    private final long prevLogIndex;
    private final long prevLogTerm;
    private final List<LogEntry> entries;
    private final long leaderCommit;

    public AppendEntriesRequest(long term, NodeId leaderId, long prevLogIndex, long prevLogTerm,
                                List<LogEntry> entries, long leaderCommit) {
        this.term = term;
        this.leaderId = leaderId;
        this.prevLogIndex = prevLogIndex;
        this.prevLogTerm = prevLogTerm;
        this.entries = List.copyOf(entries);
        this.leaderCommit = leaderCommit;
    }

    public long getTerm() { return term; }
    public NodeId getLeaderId() { return leaderId; }
    public long getPrevLogIndex() { return prevLogIndex; }
    public long getPrevLogTerm() { return prevLogTerm; }
    public List<LogEntry> getEntries() { return entries; }
    public long getLeaderCommit() { return leaderCommit; }
}
