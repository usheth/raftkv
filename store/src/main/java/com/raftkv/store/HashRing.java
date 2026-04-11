package com.raftkv.store;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent hash ring that partitions keys across shard IDs using virtual nodes.
 *
 * <p>By default 256 vnodes are spread evenly across the registered shard IDs.
 * Each vnode is a contiguous slot in the integer range [0, numVnodes). The
 * vnode for a key is determined by:
 * <pre>
 *   vnode = (int) (murmur3_128(key).asLong() & 0x7FFF_FFFF_FFFF_FFFFL) % numVnodes
 * </pre>
 *
 * <p>Ring mutations ({@link #addShard} / {@link #removeShard}) redistribute
 * vnodes and must be driven through the config shard (shard 0) in the full
 * system.  This class is the pure data structure; coordination is the caller's
 * responsibility.
 */
public class HashRing {

    public static final int DEFAULT_VNODE_COUNT = 256;

    private static final HashFunction HASH_FN = Hashing.murmur3_128();

    private final int numVnodes;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** vnode index → shard ID */
    private final int[] ring;

    /** live shard IDs (ordered for deterministic redistribution) */
    private final List<Integer> shards = new ArrayList<>();

    /**
     * Builds a ring with the supplied shard IDs and the default vnode count (256).
     */
    public HashRing(List<Integer> shardIds) {
        this(shardIds, DEFAULT_VNODE_COUNT);
    }

    /**
     * Builds a ring with the supplied shard IDs and a custom vnode count.
     */
    public HashRing(List<Integer> shardIds, int numVnodes) {
        if (shardIds == null || shardIds.isEmpty()) {
            throw new IllegalArgumentException("At least one shard ID is required");
        }
        if (numVnodes <= 0) {
            throw new IllegalArgumentException("numVnodes must be positive");
        }
        this.numVnodes = numVnodes;
        this.ring = new int[numVnodes];
        List<Integer> sorted = new ArrayList<>(shardIds);
        Collections.sort(sorted);
        this.shards.addAll(sorted);
        distribute();
    }

    // ----- Public read API -----

    /** Returns the vnode index (0 .. numVnodes-1) that {@code key} maps to. */
    public int getVnode(String key) {
        long h = HASH_FN.hashString(key, StandardCharsets.UTF_8).asLong();
        // mask sign bit so the modulo is always non-negative
        long positive = h & 0x7FFF_FFFF_FFFF_FFFFL;
        return (int) (positive % numVnodes);
    }

    /** Returns the shard ID responsible for {@code key}. */
    public int getShard(String key) {
        lock.readLock().lock();
        try {
            return ring[getVnode(key)];
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns an unmodifiable snapshot of current shard IDs. */
    public List<Integer> getShards() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(shards));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the shard assigned to a specific vnode index. */
    public int getShardForVnode(int vnode) {
        lock.readLock().lock();
        try {
            return ring[vnode];
        } finally {
            lock.readLock().unlock();
        }
    }

    // ----- Ring mutations (must be applied via config shard 0 in production) -----

    /**
     * Adds a new shard to the ring and redistributes vnodes evenly.
     *
     * @throws IllegalArgumentException if the shard ID is already present.
     */
    public void addShard(int shardId) {
        lock.writeLock().lock();
        try {
            if (shards.contains(shardId)) {
                throw new IllegalArgumentException("Shard already exists: " + shardId);
            }
            shards.add(shardId);
            Collections.sort(shards);
            distribute();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a shard from the ring and redistributes its vnodes to the remaining shards.
     *
     * @throws IllegalArgumentException if the shard ID is not present or is the last shard.
     */
    public void removeShard(int shardId) {
        lock.writeLock().lock();
        try {
            if (!shards.contains(shardId)) {
                throw new IllegalArgumentException("Shard not found: " + shardId);
            }
            if (shards.size() == 1) {
                throw new IllegalArgumentException("Cannot remove the last shard");
            }
            shards.remove(Integer.valueOf(shardId));
            distribute();
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ----- Private helpers -----

    /**
     * Distributes vnodes evenly across {@link #shards} in a round-robin fashion.
     * Deterministic for a given ordered shard list.
     */
    private void distribute() {
        int n = shards.size();
        for (int v = 0; v < numVnodes; v++) {
            ring[v] = shards.get(v % n);
        }
    }
}
