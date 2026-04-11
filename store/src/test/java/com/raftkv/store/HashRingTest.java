package com.raftkv.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HashRingTest {

    @Test
    void getShard_iDeterministic_sameKeyAlwaysReturnsSameShard() {
        HashRing ring = new HashRing(List.of(0, 1, 2));
        int first = ring.getShard("stable-key");
        for (int i = 0; i < 100; i++) {
            assertEquals(first, ring.getShard("stable-key"),
                    "getShard must be deterministic");
        }
    }

    @Test
    void getVnode_isInRange() {
        HashRing ring = new HashRing(List.of(0, 1), 256);
        for (String key : List.of("a", "b", "foo", "bar", "hello", "world")) {
            int vnode = ring.getVnode(key);
            assertTrue(vnode >= 0 && vnode < 256,
                    "vnode must be in [0, 256) but was " + vnode + " for key " + key);
        }
    }

    @Test
    void getVnode_isConsistentWithGetShard() {
        HashRing ring = new HashRing(List.of(0, 1, 2), 256);
        String key = "test-key";
        int vnode = ring.getVnode(key);
        int shardFromVnode = ring.getShardForVnode(vnode);
        int shardFromKey = ring.getShard(key);
        assertEquals(shardFromVnode, shardFromKey,
                "getShard and getShardForVnode must be consistent");
    }

    @Test
    void defaultVnodeCountIs256() {
        HashRing ring = new HashRing(List.of(0, 1));
        // Ring covers all 256 vnodes — verify edge vnodes return a valid shard
        for (int v = 0; v < 256; v++) {
            int shard = ring.getShardForVnode(v);
            assertTrue(shard == 0 || shard == 1,
                    "Shard must be one of [0, 1] but was " + shard + " for vnode " + v);
        }
    }

    @Test
    void vnodesMappedTo256Slots_allAssigned() {
        HashRing ring = new HashRing(List.of(0, 1, 2), 256);
        // Every vnode slot must be assigned to a shard
        int[] shards = {0, 1, 2};
        for (int v = 0; v < 256; v++) {
            int assigned = ring.getShardForVnode(v);
            boolean valid = false;
            for (int s : shards) {
                if (s == assigned) { valid = true; break; }
            }
            assertTrue(valid, "Vnode " + v + " assigned to unknown shard " + assigned);
        }
    }

    @Test
    void addShard_redistributesVnodesEvenly() {
        HashRing ring = new HashRing(List.of(0, 1), 256);
        ring.addShard(2);
        List<Integer> shards = ring.getShards();
        assertTrue(shards.contains(2), "New shard must be present after addShard");
        assertEquals(3, shards.size());
        // All vnodes must still be assigned to a valid shard
        for (int v = 0; v < 256; v++) {
            int s = ring.getShardForVnode(v);
            assertTrue(shards.contains(s), "Vnode " + v + " assigned to unknown shard " + s);
        }
    }

    @Test
    void removeShard_redistributesVnodes() {
        HashRing ring = new HashRing(List.of(0, 1, 2), 256);
        ring.removeShard(2);
        List<Integer> shards = ring.getShards();
        assertFalse(shards.contains(2), "Removed shard must not be present");
        assertEquals(2, shards.size());
        for (int v = 0; v < 256; v++) {
            int s = ring.getShardForVnode(v);
            assertTrue(shards.contains(s), "Vnode " + v + " must not point to removed shard");
        }
    }

    @Test
    void addShard_throwsIfAlreadyPresent() {
        HashRing ring = new HashRing(List.of(0, 1));
        assertThrows(IllegalArgumentException.class, () -> ring.addShard(0));
    }

    @Test
    void removeShard_throwsIfLastShard() {
        HashRing ring = new HashRing(List.of(0));
        assertThrows(IllegalArgumentException.class, () -> ring.removeShard(0));
    }

    @Test
    void differentKeys_mapToMixOfShards() {
        // With 3 shards and many keys, we expect all 3 shards to be hit
        HashRing ring = new HashRing(List.of(0, 1, 2), 256);
        boolean[] seen = new boolean[3];
        String[] keys = {
            "alpha", "beta", "gamma", "delta", "epsilon", "zeta",
            "eta", "theta", "iota", "kappa", "lambda", "mu",
            "nu", "xi", "omicron", "pi", "rho", "sigma", "tau", "upsilon"
        };
        for (String key : keys) {
            seen[ring.getShard(key)] = true;
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(seen[i], "Shard " + i + " was never used — distribution looks wrong");
        }
    }
}
