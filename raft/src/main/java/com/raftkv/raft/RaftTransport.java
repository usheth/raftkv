package com.raftkv.raft;

import com.raftkv.core.NodeId;

import java.util.function.BiConsumer;

/**
 * Interface for sending outbound Raft RPCs. The real gRPC implementation lives in the
 * {@code server} module. This interface allows the {@code raft} module to remain
 * independent of transport concerns.
 */
public interface RaftTransport {
    /**
     * Sends a RequestVote RPC to the given target.
     *
     * @param target   the destination node
     * @param req      the RequestVote arguments
     * @param callback invoked (on the caller's thread or the event loop) with the response
     */
    void sendRequestVote(NodeId target, RequestVoteRequest req,
                         BiConsumer<NodeId, RequestVoteResponse> callback);

    /**
     * Sends an AppendEntries RPC to the given target.
     *
     * @param target   the destination node
     * @param req      the AppendEntries arguments
     * @param callback invoked with the response
     */
    void sendAppendEntries(NodeId target, AppendEntriesRequest req,
                           BiConsumer<NodeId, AppendEntriesResponse> callback);
}
