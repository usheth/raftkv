package com.raftkv.server;

import com.raftkv.core.NodeId;
import com.raftkv.core.SystemClock;
import com.raftkv.raft.PersistentState;
import com.raftkv.raft.RaftNode;
import com.raftkv.store.HashRing;
import com.raftkv.store.KVStore;
import com.raftkv.store.ShardRouter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for a RaftKV server node.
 *
 * <p>Reads configuration from environment variables:
 * <ul>
 *   <li>{@code RAFTKV_NODE_ID} — this node's identifier (e.g. {@code node-0})</li>
 *   <li>{@code RAFTKV_PORT}    — gRPC port to bind (default: 9090)</li>
 *   <li>{@code RAFTKV_PEERS}   — comma-separated {@code nodeId=host:port} pairs for other nodes</li>
 *   <li>{@code RAFTKV_SHARD_COUNT} — number of shards (default: 3)</li>
 *   <li>{@code RAFTKV_DATA_DIR}    — directory for persistent Raft state (default: /data/raft)</li>
 * </ul>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        // --- Read configuration from environment variables ---
        String nodeIdStr = requireEnv("RAFTKV_NODE_ID");
        int port = Integer.parseInt(System.getenv().getOrDefault("RAFTKV_PORT", "9090"));
        String peersEnv = System.getenv().getOrDefault("RAFTKV_PEERS", "");
        int shardCount = Integer.parseInt(System.getenv().getOrDefault("RAFTKV_SHARD_COUNT", "3"));
        String dataDir = System.getenv().getOrDefault("RAFTKV_DATA_DIR", "/data/raft");

        NodeId selfId = new NodeId(nodeIdStr);

        // --- Parse peers: "node-1=host:port,node-2=host:port" ---
        Map<NodeId, String[]> peerAddresses = parsePeers(peersEnv);
        List<NodeId> peerIds = new ArrayList<>(peerAddresses.keySet());

        log.info("Starting node {} on port {}, peers={}, shards={}", nodeIdStr, port, peerIds, shardCount);

        // --- Persistent state ---
        Path dataPath = Paths.get(dataDir);
        Files.createDirectories(dataPath);
        Path stateFile = dataPath.resolve(nodeIdStr + "-state.txt");
        PersistentState persistentState = new PersistentState(stateFile);

        // --- Build gRPC channels to peers ---
        Map<NodeId, ManagedChannel> channels = new HashMap<>();
        for (Map.Entry<NodeId, String[]> entry : peerAddresses.entrySet()) {
            NodeId peerId = entry.getKey();
            String host = entry.getValue()[0];
            int peerPort = Integer.parseInt(entry.getValue()[1]);
            ManagedChannel channel = ManagedChannelBuilder.forAddress(host, peerPort)
                    .usePlaintext()
                    .build();
            channels.put(peerId, channel);
        }

        // --- Single-threaded event loop for Raft state machine ---
        ScheduledExecutorService eventLoop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-event-loop");
            t.setDaemon(true);
            return t;
        });

        // --- Store layer ---
        List<Integer> shardIds = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            shardIds.add(i);
        }
        KVStore kvStore = new KVStore();
        HashRing hashRing = new HashRing(shardIds);

        // --- Transport ---
        RaftTransportImpl transport = new RaftTransportImpl(channels, List.of(), eventLoop);

        // --- StateMachineApplier: parse PUT/DELETE commands and apply to KVStore ---
        // Command format: "PUT key=value" or "DELETE key"
        com.raftkv.raft.StateMachineApplier applier = entry -> {
            String cmd = new String(entry.getCommand(), java.nio.charset.StandardCharsets.UTF_8);
            try {
                if (cmd.startsWith("PUT ")) {
                    String rest = cmd.substring(4);
                    int eq = rest.indexOf('=');
                    if (eq > 0) {
                        String key = rest.substring(0, eq);
                        String value = rest.substring(eq + 1);
                        kvStore.put(key, value);
                    }
                } else if (cmd.startsWith("DELETE ")) {
                    String key = cmd.substring(7);
                    kvStore.delete(key);
                }
            } catch (Exception e) {
                log.warn("Failed to apply command: {}", cmd, e);
            }
        };

        // --- RaftNode ---
        RaftNode raftNode = new RaftNode(
                selfId, peerIds, persistentState, new SystemClock(), transport, applier);

        // Build a simple shard router where shard 0 is always mapped to this node as leader
        // once the node becomes leader. The router leader map is updated by a background task.
        ShardRouter shardRouter = new ShardRouter(selfId, hashRing, kvStore);

        // --- gRPC services ---
        RaftServiceImpl raftService = new RaftServiceImpl(raftNode, eventLoop);
        KvServiceImpl kvService = new KvServiceImpl(kvStore, shardRouter, raftNode);

        RaftServer server = new RaftServer(port, List.of(raftService, kvService));
        server.start();

        // --- Start Raft node on event loop ---
        eventLoop.execute(raftNode::start);

        // --- Tick loop: drive Raft state machine every 10ms ---
        eventLoop.scheduleAtFixedRate(() -> {
            try {
                raftNode.tick();
                // Update leader map in ShardRouter based on current role
                if (raftNode.getRole() == com.raftkv.core.RaftRole.LEADER) {
                    for (int shardId : shardIds) {
                        shardRouter.setLeader(shardId, selfId);
                    }
                } else {
                    // If we know who the leader is, propagate to ShardRouter
                    NodeId leader = raftNode.getLeaderId();
                    for (int shardId : shardIds) {
                        if (leader != null) {
                            shardRouter.setLeader(shardId, leader);
                        } else {
                            shardRouter.clearLeader(shardId);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error in Raft tick", e);
            }
        }, 10, 10, TimeUnit.MILLISECONDS);

        // --- Shutdown hook ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down node {}", nodeIdStr);
            server.stop();
            eventLoop.shutdown();
            for (ManagedChannel ch : channels.values()) {
                ch.shutdown();
            }
        }, "shutdown-hook"));

        log.info("Node {} ready on port {}", nodeIdStr, server.getPort());

        // Block forever
        Thread.currentThread().join();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }

    /**
     * Parses {@code "nodeId=host:port,nodeId2=host2:port2"} into a map.
     */
    private static Map<NodeId, String[]> parsePeers(String peersEnv) {
        Map<NodeId, String[]> result = new HashMap<>();
        if (peersEnv == null || peersEnv.isBlank()) {
            return result;
        }
        for (String pair : peersEnv.split(",")) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("Invalid peer spec (expected nodeId=host:port): " + pair);
            }
            String nodeId = pair.substring(0, eq).trim();
            String hostPort = pair.substring(eq + 1).trim();
            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Invalid peer address (expected host:port): " + hostPort);
            }
            String host = hostPort.substring(0, colon);
            String portStr = hostPort.substring(colon + 1);
            result.put(new NodeId(nodeId), new String[]{host, portStr});
        }
        return result;
    }
}
