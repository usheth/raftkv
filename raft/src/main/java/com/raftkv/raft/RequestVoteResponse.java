package com.raftkv.raft;

/**
 * RequestVote RPC response.
 */
public final class RequestVoteResponse {
    private final long term;
    private final boolean voteGranted;

    public RequestVoteResponse(long term, boolean voteGranted) {
        this.term = term;
        this.voteGranted = voteGranted;
    }

    public long getTerm() { return term; }
    public boolean isVoteGranted() { return voteGranted; }

    @Override
    public String toString() {
        return "RequestVoteResponse{term=" + term + ", voteGranted=" + voteGranted + "}";
    }
}
