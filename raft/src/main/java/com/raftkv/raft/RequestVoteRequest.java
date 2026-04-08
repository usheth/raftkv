package com.raftkv.raft;

import com.raftkv.core.NodeId;

/**
 * RequestVote RPC arguments.
 */
public final class RequestVoteRequest {
    private final long term;
    private final NodeId candidateId;
    private final long lastLogIndex;
    private final long lastLogTerm;

    public RequestVoteRequest(long term, NodeId candidateId, long lastLogIndex, long lastLogTerm) {
        this.term = term;
        this.candidateId = candidateId;
        this.lastLogIndex = lastLogIndex;
        this.lastLogTerm = lastLogTerm;
    }

    public long getTerm() { return term; }
    public NodeId getCandidateId() { return candidateId; }
    public long getLastLogIndex() { return lastLogIndex; }
    public long getLastLogTerm() { return lastLogTerm; }

    @Override
    public String toString() {
        return "RequestVoteRequest{term=" + term + ", candidateId=" + candidateId + "}";
    }
}
