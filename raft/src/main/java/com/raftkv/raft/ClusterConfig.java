package com.raftkv.raft;

import com.raftkv.core.NodeId;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable cluster configuration, used for joint consensus (§6 of the Raft paper).
 *
 * <p>During the joint phase both {@code oldPeers} and {@code newPeers} are non-null
 * and quorum requires a majority of <em>each</em> set. After C_new is committed,
 * {@code oldPeers} is null (or equal to newPeers) and only the new membership matters.
 */
public final class ClusterConfig {

    /** The "before" peer set (null or empty when this is already a C_new config). */
    private final Set<NodeId> oldPeers;

    /** The "after" peer set — always present. */
    private final Set<NodeId> newPeers;

    /**
     * Creates a simple (non-joint) config from a single peer set.
     */
    public static ClusterConfig simple(Set<NodeId> peers) {
        return new ClusterConfig(null, Set.copyOf(peers));
    }

    /**
     * Creates a joint (C_old,new) config.
     */
    public static ClusterConfig joint(Set<NodeId> oldPeers, Set<NodeId> newPeers) {
        return new ClusterConfig(Set.copyOf(oldPeers), Set.copyOf(newPeers));
    }

    private ClusterConfig(Set<NodeId> oldPeers, Set<NodeId> newPeers) {
        this.oldPeers = oldPeers;
        this.newPeers = Set.copyOf(newPeers);
    }

    public boolean isJoint() {
        return oldPeers != null;
    }

    public Set<NodeId> getOldPeers() {
        return oldPeers != null ? oldPeers : Collections.emptySet();
    }

    public Set<NodeId> getNewPeers() {
        return newPeers;
    }

    /**
     * Returns the union of old and new peers (all nodes that must receive log entries
     * during the joint phase). For a simple config this is just newPeers.
     */
    public Set<NodeId> allPeers() {
        if (oldPeers == null) return newPeers;
        Set<NodeId> union = new HashSet<>(oldPeers);
        union.addAll(newPeers);
        return Collections.unmodifiableSet(union);
    }

    /**
     * Serialises the config to a byte array that can be stored as a log entry command.
     *
     * <p>Format: {@code C_SIMPLE:<nodeId>,<nodeId>,...}
     * or {@code C_JOINT:<old1>,<old2>,...|<new1>,<new2>,...}
     */
    public byte[] encode() {
        StringBuilder sb = new StringBuilder();
        if (oldPeers == null) {
            sb.append("C_SIMPLE:");
            appendIds(sb, newPeers);
        } else {
            sb.append("C_JOINT:");
            appendIds(sb, oldPeers);
            sb.append("|");
            appendIds(sb, newPeers);
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Deserialises a {@link ClusterConfig} from bytes previously produced by {@link #encode()}.
     */
    public static ClusterConfig decode(byte[] data) {
        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (s.startsWith("C_SIMPLE:")) {
            String ids = s.substring("C_SIMPLE:".length());
            return ClusterConfig.simple(parseIds(ids));
        } else if (s.startsWith("C_JOINT:")) {
            String rest = s.substring("C_JOINT:".length());
            int sep = rest.indexOf('|');
            if (sep < 0) throw new IllegalArgumentException("Malformed C_JOINT: " + s);
            Set<NodeId> old = parseIds(rest.substring(0, sep));
            Set<NodeId> nw  = parseIds(rest.substring(sep + 1));
            return ClusterConfig.joint(old, nw);
        } else {
            throw new IllegalArgumentException("Unknown config encoding: " + s);
        }
    }

    /**
     * Returns true if the byte array looks like an encoded ClusterConfig.
     */
    public static boolean isConfigEntry(byte[] data) {
        if (data == null || data.length < 9) return false;
        String s = new String(data, 0, Math.min(data.length, 8),
                java.nio.charset.StandardCharsets.UTF_8);
        return s.startsWith("C_SIMPLE") || s.startsWith("C_JOINT:");
    }

    // -------------------------------------------------------------------------

    private static void appendIds(StringBuilder sb, Set<NodeId> ids) {
        boolean first = true;
        // Sort for determinism
        java.util.List<String> sorted = new java.util.ArrayList<>();
        for (NodeId n : ids) sorted.add(n.getId());
        java.util.Collections.sort(sorted);
        for (String id : sorted) {
            if (!first) sb.append(",");
            sb.append(id);
            first = false;
        }
    }

    private static Set<NodeId> parseIds(String s) {
        Set<NodeId> result = new HashSet<>();
        if (s == null || s.isEmpty()) return result;
        for (String id : s.split(",")) {
            if (!id.isEmpty()) result.add(new NodeId(id));
        }
        return result;
    }

    @Override
    public String toString() {
        if (oldPeers == null) return "ClusterConfig{new=" + newPeers + "}";
        return "ClusterConfig{old=" + oldPeers + ", new=" + newPeers + "}";
    }
}
