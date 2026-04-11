package com.raftkv.server;

import com.google.protobuf.ByteString;
import com.raftkv.core.ManualClock;
import com.raftkv.core.NodeId;
import com.raftkv.proto.DeleteRequest;
import com.raftkv.proto.DeleteResponse;
import com.raftkv.proto.GetRequest;
import com.raftkv.proto.GetResponse;
import com.raftkv.proto.KvServiceGrpc;
import com.raftkv.proto.PutRequest;
import com.raftkv.proto.PutResponse;
import com.raftkv.proto.Status;
import com.raftkv.raft.PersistentState;
import com.raftkv.raft.RaftNode;
import com.raftkv.raft.RaftTransport;
import com.raftkv.raft.StateMachineApplier;
import com.raftkv.store.HashRing;
import com.raftkv.store.KVStore;
import com.raftkv.store.ShardRouter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link KvServiceImpl} running behind a real gRPC server.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Put routes through ShardRouter and stores value when local node is leader</li>
 *   <li>Get returns the correct value after a Put</li>
 *   <li>Put returns NOT_LEADER when the local node is not the leader for the shard</li>
 * </ul>
 */
class KvServiceImplTest {

    @TempDir
    Path tempDir;

    private Server grpcServer;
    private ManagedChannel channel;
    private KVStore kvStore;
    private ShardRouter shardRouter;
    private RaftNode raftNode;

    private static final NodeId LOCAL_NODE = new NodeId("node1");
    private static final int SHARD_0 = 0;

    @BeforeEach
    void setUp() throws IOException {
        kvStore = new KVStore();
        HashRing hashRing = new HashRing(List.of(SHARD_0));
        shardRouter = new ShardRouter(LOCAL_NODE, hashRing, kvStore);

        PersistentState storage = new PersistentState(tempDir.resolve("raft.log"));
        RaftTransport noOpTransport = new RaftTransport() {
            public void sendRequestVote(NodeId t, com.raftkv.raft.RequestVoteRequest r,
                    java.util.function.BiConsumer<NodeId, com.raftkv.raft.RequestVoteResponse> cb) {}
            public void sendAppendEntries(NodeId t, com.raftkv.raft.AppendEntriesRequest r,
                    java.util.function.BiConsumer<NodeId, com.raftkv.raft.AppendEntriesResponse> cb) {}
        };
        // Applier decodes PUT/DELETE commands and applies them to kvStore (mirrors Main.java)
        StateMachineApplier applier = entry -> {
            String cmd = new String(entry.getCommand(), java.nio.charset.StandardCharsets.UTF_8);
            if (cmd.startsWith("PUT ")) {
                String rest = cmd.substring(4);
                int eq = rest.indexOf('=');
                if (eq > 0) {
                    kvStore.put(rest.substring(0, eq), rest.substring(eq + 1));
                }
            } else if (cmd.startsWith("DELETE ")) {
                kvStore.delete(cmd.substring(7));
            }
        };
        ManualClock clock = new ManualClock();
        raftNode = new RaftNode(LOCAL_NODE, List.of(), storage, clock,
                noOpTransport, applier);
        raftNode.start();
        // Single-node cluster: advance clock past election timeout and tick to become leader
        clock.advance(500);
        raftNode.tick();

        KvServiceImpl kvService = new KvServiceImpl(kvStore, shardRouter, raftNode);

        grpcServer = ServerBuilder.forPort(0).addService(kvService).build().start();
        channel = ManagedChannelBuilder
                .forAddress("localhost", grpcServer.getPort())
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        grpcServer.shutdownNow().awaitTermination();
    }

    /**
     * Makes the local node the leader for shard 0 by registering it in the ShardRouter.
     */
    private void makeLocalNodeLeader() {
        shardRouter.setLeader(SHARD_0, LOCAL_NODE);
    }

    @Test
    void putAndGetRoundTrip() {
        makeLocalNodeLeader();
        KvServiceGrpc.KvServiceBlockingStub stub = KvServiceGrpc.newBlockingStub(channel);

        // Put a value
        PutResponse putResp = stub.put(PutRequest.newBuilder()
                .setKey("hello")
                .setValue(ByteString.copyFromUtf8("world"))
                .build());
        assertEquals(Status.OK, putResp.getStatus());

        // Get it back
        GetResponse getResp = stub.get(GetRequest.newBuilder()
                .setKey("hello")
                .build());
        assertEquals(Status.OK, getResp.getStatus());
        assertEquals("world", getResp.getValue().toStringUtf8());
    }

    @Test
    void putReturnsNotLeaderWhenNotLeader() {
        // Do NOT call makeLocalNodeLeader() — local node has no leader registered
        KvServiceGrpc.KvServiceBlockingStub stub = KvServiceGrpc.newBlockingStub(channel);

        PutResponse putResp = stub.put(PutRequest.newBuilder()
                .setKey("k")
                .setValue(ByteString.copyFromUtf8("v"))
                .build());
        assertEquals(Status.NOT_LEADER, putResp.getStatus());
    }

    @Test
    void getReturnsKeyNotFoundForMissingKey() {
        makeLocalNodeLeader();
        KvServiceGrpc.KvServiceBlockingStub stub = KvServiceGrpc.newBlockingStub(channel);

        GetResponse getResp = stub.get(GetRequest.newBuilder()
                .setKey("nonexistent")
                .build());
        assertEquals(Status.KEY_NOT_FOUND, getResp.getStatus());
    }

    @Test
    void deleteRemovesKey() {
        makeLocalNodeLeader();
        KvServiceGrpc.KvServiceBlockingStub stub = KvServiceGrpc.newBlockingStub(channel);

        // Put first
        stub.put(PutRequest.newBuilder()
                .setKey("toDelete")
                .setValue(ByteString.copyFromUtf8("value"))
                .build());

        // Delete
        DeleteResponse deleteResp = stub.delete(DeleteRequest.newBuilder()
                .setKey("toDelete")
                .build());
        assertEquals(Status.OK, deleteResp.getStatus());

        // Get should now return KEY_NOT_FOUND
        GetResponse getResp = stub.get(GetRequest.newBuilder()
                .setKey("toDelete")
                .build());
        assertEquals(Status.KEY_NOT_FOUND, getResp.getStatus());
    }

    @Test
    void putWithLeaderHintReturnsHintWhenKnown() {
        // Set a different leader so we get a hint
        NodeId otherLeader = new NodeId("node2");
        shardRouter.setLeader(SHARD_0, otherLeader);

        KvServiceGrpc.KvServiceBlockingStub stub = KvServiceGrpc.newBlockingStub(channel);
        PutResponse resp = stub.put(PutRequest.newBuilder()
                .setKey("k")
                .setValue(ByteString.copyFromUtf8("v"))
                .build());

        assertEquals(Status.NOT_LEADER, resp.getStatus());
        assertEquals("node2", resp.getLeaderHint());
    }
}
