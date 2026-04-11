package com.raftkv.store;

import com.raftkv.core.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShardRouterTest {

    private static final NodeId LOCAL = new NodeId("node-0");
    private static final NodeId OTHER = new NodeId("node-1");

    private KVStore kvStore;
    private HashRing ring;
    private ShardRouter router;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore();
        // Two shards: 0 and 1; 256 vnodes
        ring = new HashRing(List.of(0, 1), 256);
        router = new ShardRouter(LOCAL, ring, kvStore);
    }

    // ---- Ok path ----

    @Test
    void routeReturnsOk_whenLocalNodeIsLeader() {
        String key = findKeyForShard(0);
        router.setLeader(0, LOCAL);

        RoutingResult result = router.route(key, false);

        assertInstanceOf(RoutingResult.Ok.class, result);
        RoutingResult.Ok ok = (RoutingResult.Ok) result;
        assertEquals(0, ok.shardId());
        assertEquals(LOCAL, ok.leaderId());
    }

    @Test
    void routeReturnsOk_forWriteWhenNotFenced() {
        String key = findKeyForShard(0);
        router.setLeader(0, LOCAL);

        RoutingResult result = router.route(key, true);

        assertInstanceOf(RoutingResult.Ok.class, result);
    }

    // ---- NotLeader path ----

    @Test
    void routeReturnsNotLeaderWithHint_whenOtherNodeIsLeader() {
        String key = findKeyForShard(0);
        router.setLeader(0, OTHER);

        RoutingResult result = router.route(key, false);

        assertInstanceOf(RoutingResult.NotLeader.class, result);
        RoutingResult.NotLeader nl = (RoutingResult.NotLeader) result;
        assertTrue(nl.hint().isPresent(), "Hint must be present when leader is known");
        assertEquals(OTHER, nl.hint().get());
    }

    @Test
    void routeReturnsNotLeaderNoHint_whenLeaderUnknown() {
        // No leader registered for shard 0
        String key = findKeyForShard(0);

        RoutingResult result = router.route(key, false);

        assertInstanceOf(RoutingResult.NotLeader.class, result);
        RoutingResult.NotLeader nl = (RoutingResult.NotLeader) result;
        assertFalse(nl.hint().isPresent(), "Hint must be absent when leader is unknown");
    }

    @Test
    void clearLeader_causesNoHintResult() {
        String key = findKeyForShard(0);
        router.setLeader(0, OTHER);
        router.clearLeader(0);

        RoutingResult result = router.route(key, false);

        assertInstanceOf(RoutingResult.NotLeader.class, result);
        RoutingResult.NotLeader nl = (RoutingResult.NotLeader) result;
        assertFalse(nl.hint().isPresent());
    }

    // ---- Resharding path ----

    @Test
    void routeReturnsResharding_whenVnodeIsFencedAndRequestIsWrite() {
        String key = findKeyForShard(0);
        int vnode = ring.getVnode(key);
        kvStore.fenceVnode(vnode);
        router.setLeader(0, LOCAL);  // local is leader, but fencing wins

        RoutingResult result = router.route(key, true);

        assertInstanceOf(RoutingResult.Resharding.class, result);
        RoutingResult.Resharding rs = (RoutingResult.Resharding) result;
        assertEquals(vnode, rs.vnode());
    }

    @Test
    void routeDoesNotReturnResharding_forReadOnFencedVnode() {
        // Fencing only applies to writes; reads are not blocked at the router level
        String key = findKeyForShard(0);
        int vnode = ring.getVnode(key);
        kvStore.fenceVnode(vnode);
        router.setLeader(0, LOCAL);

        RoutingResult result = router.route(key, false);

        // Should be Ok (not Resharding) for a read
        assertInstanceOf(RoutingResult.Ok.class, result);
    }

    @Test
    void routeDefaultOverload_treatsAsRead() {
        String key = findKeyForShard(0);
        int vnode = ring.getVnode(key);
        kvStore.fenceVnode(vnode);
        router.setLeader(0, LOCAL);

        RoutingResult result = router.route(key);  // no isWrite arg

        // Default route() is read — should not return Resharding
        assertInstanceOf(RoutingResult.Ok.class, result);
    }

    @Test
    void unfencingVnode_allowsWriteThrough() {
        String key = findKeyForShard(0);
        int vnode = ring.getVnode(key);
        kvStore.fenceVnode(vnode);
        kvStore.unfenceVnode(vnode);
        router.setLeader(0, LOCAL);

        RoutingResult result = router.route(key, true);

        assertInstanceOf(RoutingResult.Ok.class, result);
    }

    // ---- Helper ----

    /**
     * Finds a key that the ring assigns to the requested shard.
     * Uses deterministic prefix scanning to keep tests fast.
     */
    private String findKeyForShard(int targetShard) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = "key-" + i;
            if (ring.getShard(candidate) == targetShard) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a key for shard " + targetShard);
    }
}
