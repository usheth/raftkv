package com.raftkv.store;

import com.raftkv.core.NodeId;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Routes client requests to the correct shard based on the {@link HashRing}.
 *
 * <p>The router holds a view of which node is the current leader per shard.
 * This view is updated externally (e.g. from the Raft layer via the server
 * module).  The router itself has no dependency on the Raft module; it only
 * knows about the current leader's {@link NodeId} for each shard.
 *
 * <h2>Routing logic</h2>
 * <ol>
 *   <li>Determine vnode and shard from the {@link HashRing}.</li>
 *   <li>If {@code isWrite} and the vnode is fenced in the {@link KVStore},
 *       return {@link RoutingResult.Resharding}.</li>
 *   <li>If this node ({@link #localNodeId}) is the leader for that shard,
 *       return {@link RoutingResult.Ok}.</li>
 *   <li>Otherwise return {@link RoutingResult.NotLeader} with the known
 *       leader as a hint (if any).</li>
 * </ol>
 */
public class ShardRouter {

    private final NodeId localNodeId;
    private final HashRing hashRing;
    private final KVStore kvStore;

    /** shardId → current leader NodeId (may be absent if leader is unknown) */
    private final ConcurrentMap<Integer, NodeId> leaderMap = new ConcurrentHashMap<>();

    public ShardRouter(NodeId localNodeId, HashRing hashRing, KVStore kvStore) {
        this.localNodeId = localNodeId;
        this.hashRing = hashRing;
        this.kvStore = kvStore;
    }

    /**
     * Routes a request for {@code key}.
     *
     * @param key     the key being accessed
     * @param isWrite true if this is a write (put/delete); false for reads.
     *                Fencing is only checked for writes.
     * @return a {@link RoutingResult} describing how the request should be handled.
     */
    public RoutingResult route(String key, boolean isWrite) {
        int vnode = hashRing.getVnode(key);
        int shardId = hashRing.getShardForVnode(vnode);

        // Fencing check — writes to a migrating vnode are rejected
        if (isWrite && kvStore.isFenced(vnode)) {
            return new RoutingResult.Resharding(vnode);
        }

        // Leader check
        NodeId leader = leaderMap.get(shardId);
        if (localNodeId.equals(leader)) {
            return new RoutingResult.Ok(shardId, localNodeId);
        }

        // Not leader — return hint if we know who is
        return leader != null ? RoutingResult.NotLeader.of(leader) : RoutingResult.NotLeader.noHint();
    }

    /**
     * Convenience overload for read-only routing (no fencing check).
     */
    public RoutingResult route(String key) {
        return route(key, false);
    }

    // ----- Leader registry -----

    /**
     * Updates the known leader for a shard.  Called by the server layer when
     * it learns of a new leader (e.g. via AppendEntries or RequestVote).
     */
    public void setLeader(int shardId, NodeId leaderId) {
        leaderMap.put(shardId, leaderId);
    }

    /**
     * Clears the known leader for a shard (e.g. after a leadership change
     * where the new leader is not yet known).
     */
    public void clearLeader(int shardId) {
        leaderMap.remove(shardId);
    }

    /** Returns the known leader for {@code shardId}, or empty if unknown. */
    public Optional<NodeId> getLeader(int shardId) {
        return Optional.ofNullable(leaderMap.get(shardId));
    }
}
