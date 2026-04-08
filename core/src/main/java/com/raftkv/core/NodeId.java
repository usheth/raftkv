package com.raftkv.core;

import java.util.Objects;

/**
 * Immutable value type wrapping a String node identifier.
 */
public final class NodeId {
    private final String id;

    public NodeId(String id) {
        Objects.requireNonNull(id, "id must not be null");
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeId)) return false;
        NodeId other = (NodeId) o;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
