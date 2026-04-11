package com.raftkv.server;

import com.raftkv.core.ManualClock;
import com.raftkv.core.NodeId;
import com.raftkv.core.SystemClock;
import com.raftkv.raft.PersistentState;
import com.raftkv.raft.RaftNode;
import com.raftkv.raft.RaftTransport;
import com.raftkv.raft.StateMachineApplier;
import com.raftkv.store.HashRing;
import com.raftkv.store.KVStore;
import com.raftkv.store.ShardRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link RaftServer} starts, binds to an ephemeral port, and {@link RaftServer#getPort()}
 * returns the actual bound port.
 */
class RaftServerTest {

    @TempDir
    Path tempDir;

    private RaftServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private RaftNode buildRaftNode(Path dir) throws IOException {
        NodeId selfId = new NodeId("node1");
        PersistentState storage = new PersistentState(dir.resolve("raft.log"));
        RaftTransport noOpTransport = new RaftTransport() {
            public void sendRequestVote(NodeId t, com.raftkv.raft.RequestVoteRequest r,
                    java.util.function.BiConsumer<NodeId, com.raftkv.raft.RequestVoteResponse> cb) {}
            public void sendAppendEntries(NodeId t, com.raftkv.raft.AppendEntriesRequest r,
                    java.util.function.BiConsumer<NodeId, com.raftkv.raft.AppendEntriesResponse> cb) {}
        };
        StateMachineApplier noOpApplier = entry -> {};
        return new RaftNode(selfId, List.of(), storage, new ManualClock(), noOpTransport, noOpApplier);
    }

    @Test
    void serverStartsAndBindsToEphemeralPort() throws IOException {
        RaftNode raftNode = buildRaftNode(tempDir);
        raftNode.start();

        KVStore kvStore = new KVStore();
        HashRing hashRing = new HashRing(List.of(0));
        ShardRouter shardRouter = new ShardRouter(new NodeId("node1"), hashRing, kvStore);

        RaftServiceImpl raftService = new RaftServiceImpl(raftNode, Runnable::run);
        KvServiceImpl kvService = new KvServiceImpl(kvStore, shardRouter, raftNode);

        server = new RaftServer(0, List.of(raftService, kvService));

        // Before start, getPort should throw
        assertThrows(IllegalStateException.class, () -> server.getPort());

        server.start();

        int port = server.getPort();
        assertTrue(port > 0, "Port should be a positive number after start, got: " + port);
        assertTrue(port <= 65535, "Port should be within valid range");
    }

    @Test
    void serverGetPortReturnsDifferentPortsForDifferentInstances() throws IOException {
        RaftNode raftNode1 = buildRaftNode(tempDir.resolve("n1"));
        raftNode1.start();
        RaftNode raftNode2 = buildRaftNode(tempDir.resolve("n2"));
        raftNode2.start();

        KVStore kvStore1 = new KVStore();
        HashRing hashRing1 = new HashRing(List.of(0));
        ShardRouter shardRouter1 = new ShardRouter(new NodeId("node1"), hashRing1, kvStore1);

        KVStore kvStore2 = new KVStore();
        HashRing hashRing2 = new HashRing(List.of(0));
        ShardRouter shardRouter2 = new ShardRouter(new NodeId("node2"), hashRing2, kvStore2);

        RaftServer server1 = new RaftServer(0, List.of(
                new RaftServiceImpl(raftNode1, Runnable::run),
                new KvServiceImpl(kvStore1, shardRouter1, raftNode1)));
        RaftServer server2 = new RaftServer(0, List.of(
                new RaftServiceImpl(raftNode2, Runnable::run),
                new KvServiceImpl(kvStore2, shardRouter2, raftNode2)));

        try {
            server1.start();
            server2.start();

            int port1 = server1.getPort();
            int port2 = server2.getPort();

            assertTrue(port1 > 0);
            assertTrue(port2 > 0);
            assertNotEquals(port1, port2, "Two servers should bind to different ephemeral ports");
        } finally {
            server1.stop();
            server2.stop();
        }
    }
}
