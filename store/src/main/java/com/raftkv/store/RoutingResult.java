package com.raftkv.store;

import com.raftkv.core.NodeId;

import java.util.Optional;

/**
 * Sealed result type returned by {@link ShardRouter#route(String, boolean)}.
 *
 * <p>Three variants:
 * <ul>
 *   <li>{@link Ok}         — the local node is leader for the target shard.</li>
 *   <li>{@link NotLeader}  — the local node is not leader; includes an optional hint.</li>
 *   <li>{@link Resharding} — the target vnode is currently fenced for migration.</li>
 * </ul>
 */
public sealed interface RoutingResult permits RoutingResult.Ok, RoutingResult.NotLeader, RoutingResult.Resharding {

    /** Request can be served locally. */
    record Ok(int shardId, NodeId leaderId) implements RoutingResult {}

    /** Local node is not the leader; client should redirect to {@code hint} if present. */
    record NotLeader(Optional<NodeId> hint) implements RoutingResult {
        public static NotLeader of(NodeId hint) {
            return new NotLeader(Optional.of(hint));
        }
        public static NotLeader noHint() {
            return new NotLeader(Optional.empty());
        }
    }

    /** The target vnode is fenced; client should back off and retry. */
    record Resharding(int vnode) implements RoutingResult {}
}
