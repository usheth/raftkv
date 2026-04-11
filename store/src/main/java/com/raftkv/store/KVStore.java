package com.raftkv.store;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory key-value store with vnode fencing support.
 *
 * Fencing is used during shard migration: when a vnode is fenced, writes
 * targeting that vnode are rejected with a RESHARDING error via ShardRouter.
 * Reads are still allowed through fenced vnodes (the store itself does not
 * enforce read restrictions; that policy lives in ShardRouter).
 */
public class KVStore {

    private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();
    private final Set<Integer> fencedVnodes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Returns the value for {@code key}, or empty if absent. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    /** Stores {@code value} under {@code key}. */
    public void put(String key, String value) {
        data.put(key, value);
    }

    /**
     * Removes {@code key} from the store.
     *
     * @return true if the key existed and was removed, false otherwise.
     */
    public boolean delete(String key) {
        return data.remove(key) != null;
    }

    // ----- Vnode fencing -----

    /** Marks {@code vnode} as fenced; writes to this vnode will be rejected. */
    public void fenceVnode(int vnode) {
        fencedVnodes.add(vnode);
    }

    /** Removes the fence from {@code vnode}. */
    public void unfenceVnode(int vnode) {
        fencedVnodes.remove(vnode);
    }

    /** Returns true if {@code vnode} is currently fenced. */
    public boolean isFenced(int vnode) {
        return fencedVnodes.contains(vnode);
    }
}
