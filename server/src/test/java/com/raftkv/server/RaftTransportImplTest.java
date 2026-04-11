package com.raftkv.server;

import com.google.protobuf.ByteString;
import com.raftkv.core.ManualClock;
import com.raftkv.core.NodeId;
import com.raftkv.proto.AppendEntriesResponse;
import com.raftkv.proto.LogEntryProto;
import com.raftkv.proto.RaftServiceGrpc;
import com.raftkv.proto.RequestVoteResponse;
import com.raftkv.raft.PersistentState;
import com.raftkv.raft.RaftNode;
import com.raftkv.raft.StateMachineApplier;
import com.raftkv.store.HashRing;
import com.raftkv.store.KVStore;
import com.raftkv.store.ShardRouter;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link RaftTransportImpl}:
 * 1. Can send RequestVote RPCs and have callbacks invoked on the event loop
 * 2. Can send AppendEntries RPCs and have callbacks invoked on the event loop
 * 3. Injectable {@link ClientInterceptor}s are invoked during RPC calls
 */
class RaftTransportImplTest {

    @TempDir
    Path tempDir;

    private Server peerServer;
    private ManagedChannel channel;
    private final AtomicInteger interceptorCallCount = new AtomicInteger(0);

    /** A fake RaftService server that immediately returns canned responses. */
    private final RaftServiceGrpc.RaftServiceImplBase fakeService =
            new RaftServiceGrpc.RaftServiceImplBase() {
                @Override
                public void requestVote(
                        com.raftkv.proto.RequestVoteRequest request,
                        StreamObserver<RequestVoteResponse> responseObserver) {
                    responseObserver.onNext(RequestVoteResponse.newBuilder()
                            .setTerm(1).setVoteGranted(true).build());
                    responseObserver.onCompleted();
                }

                @Override
                public void appendEntries(
                        com.raftkv.proto.AppendEntriesRequest request,
                        StreamObserver<AppendEntriesResponse> responseObserver) {
                    responseObserver.onNext(AppendEntriesResponse.newBuilder()
                            .setTerm(1).setSuccess(true).build());
                    responseObserver.onCompleted();
                }
            };

    @BeforeEach
    void setUp() throws IOException {
        peerServer = ServerBuilder.forPort(0).addService(fakeService).build().start();
        channel = ManagedChannelBuilder
                .forAddress("localhost", peerServer.getPort())
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        peerServer.shutdownNow().awaitTermination();
    }

    private ClientInterceptor countingInterceptor() {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                interceptorCallCount.incrementAndGet();
                return next.newCall(method, callOptions);
            }
        };
    }

    @Test
    void sendRequestVoteDeliversResponseViaCallback() throws Exception {
        NodeId peerId = new NodeId("peer1");
        var executor = Executors.newSingleThreadExecutor();

        RaftTransportImpl transport = new RaftTransportImpl(
                Map.of(peerId, channel),
                List.of(),
                executor);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<com.raftkv.raft.RequestVoteResponse> received = new AtomicReference<>();

        com.raftkv.raft.RequestVoteRequest req =
                new com.raftkv.raft.RequestVoteRequest(1, new NodeId("me"), 0, 0);

        transport.sendRequestVote(peerId, req, (from, resp) -> {
            received.set(resp);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should be invoked within 5s");
        assertNotNull(received.get());
        assertTrue(received.get().isVoteGranted());
        assertEquals(1, received.get().getTerm());

        executor.shutdown();
    }

    @Test
    void sendAppendEntriesDeliversResponseViaCallback() throws Exception {
        NodeId peerId = new NodeId("peer1");
        var executor = Executors.newSingleThreadExecutor();

        RaftTransportImpl transport = new RaftTransportImpl(
                Map.of(peerId, channel),
                List.of(),
                executor);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<com.raftkv.raft.AppendEntriesResponse> received = new AtomicReference<>();

        com.raftkv.raft.AppendEntriesRequest req =
                new com.raftkv.raft.AppendEntriesRequest(1, new NodeId("me"), 0, 0, List.of(), 0);

        transport.sendAppendEntries(peerId, req, (from, resp) -> {
            received.set(resp);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should be invoked within 5s");
        assertNotNull(received.get());
        assertTrue(received.get().isSuccess());

        executor.shutdown();
    }

    @Test
    void injectableInterceptorsAreInvokedOnRequestVote() throws Exception {
        NodeId peerId = new NodeId("peer1");
        var executor = Executors.newSingleThreadExecutor();

        interceptorCallCount.set(0);
        RaftTransportImpl transport = new RaftTransportImpl(
                Map.of(peerId, channel),
                List.of(countingInterceptor()),
                executor);

        CountDownLatch latch = new CountDownLatch(1);

        com.raftkv.raft.RequestVoteRequest req =
                new com.raftkv.raft.RequestVoteRequest(1, new NodeId("me"), 0, 0);

        transport.sendRequestVote(peerId, req, (from, resp) -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(interceptorCallCount.get() > 0,
                "Interceptor should have been called at least once, was: " + interceptorCallCount.get());

        executor.shutdown();
    }

    @Test
    void injectableInterceptorsAreInvokedOnAppendEntries() throws Exception {
        NodeId peerId = new NodeId("peer1");
        var executor = Executors.newSingleThreadExecutor();

        interceptorCallCount.set(0);
        RaftTransportImpl transport = new RaftTransportImpl(
                Map.of(peerId, channel),
                List.of(countingInterceptor()),
                executor);

        CountDownLatch latch = new CountDownLatch(1);

        com.raftkv.raft.AppendEntriesRequest req =
                new com.raftkv.raft.AppendEntriesRequest(1, new NodeId("me"), 0, 0, List.of(), 0);

        transport.sendAppendEntries(peerId, req, (from, resp) -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(interceptorCallCount.get() > 0,
                "Interceptor should have been called at least once, was: " + interceptorCallCount.get());

        executor.shutdown();
    }

    @Test
    void callbacksAreSubmittedToEventLoopExecutor() throws Exception {
        NodeId peerId = new NodeId("peer1");
        var executor = Executors.newSingleThreadExecutor();
        AtomicBoolean callbackOnExpectedThread = new AtomicBoolean(false);
        String expectedThreadPrefix = "pool-";

        CountDownLatch latch = new CountDownLatch(1);

        RaftTransportImpl transport = new RaftTransportImpl(
                Map.of(peerId, channel),
                List.of(),
                task -> {
                    executor.execute(() -> {
                        callbackOnExpectedThread.set(
                                Thread.currentThread().getName().contains("pool"));
                        task.run();
                    });
                });

        com.raftkv.raft.RequestVoteRequest req =
                new com.raftkv.raft.RequestVoteRequest(1, new NodeId("me"), 0, 0);

        transport.sendRequestVote(peerId, req, (from, resp) -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        executor.shutdown();
    }
}
