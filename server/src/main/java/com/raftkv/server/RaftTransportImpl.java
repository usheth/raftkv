package com.raftkv.server;

import com.google.protobuf.ByteString;
import com.raftkv.core.LogEntry;
import com.raftkv.core.NodeId;
import com.raftkv.proto.AppendEntriesRequest;
import com.raftkv.proto.AppendEntriesResponse;
import com.raftkv.proto.LogEntryProto;
import com.raftkv.proto.RaftServiceGrpc;
import com.raftkv.proto.RequestVoteRequest;
import com.raftkv.proto.RequestVoteResponse;
import com.raftkv.raft.RaftTransport;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * gRPC implementation of {@link RaftTransport}.
 *
 * <p>Accepts a map of pre-built {@link ManagedChannel}s (one per peer) and an optional
 * list of {@link ClientInterceptor}s that are applied to every channel. The interceptors
 * allow tests to simulate network faults on a per-link basis.
 *
 * <p>RPC callbacks are submitted to the supplied {@link Executor} (the node's single-threaded
 * event loop) so that all Raft state mutations stay on one thread.
 */
public class RaftTransportImpl implements RaftTransport {

    private final Map<NodeId, ManagedChannel> channels;
    private final List<ClientInterceptor> interceptors;
    private final Executor eventLoop;

    /**
     * @param channels     per-peer ManagedChannels (keyed by peer NodeId)
     * @param interceptors client interceptors applied to every channel (may be empty)
     * @param eventLoop    executor to which RPC response callbacks are submitted
     */
    public RaftTransportImpl(Map<NodeId, ManagedChannel> channels,
                             List<ClientInterceptor> interceptors,
                             Executor eventLoop) {
        this.channels = Map.copyOf(channels);
        this.interceptors = List.copyOf(interceptors);
        this.eventLoop = eventLoop;
    }

    @Override
    public void sendRequestVote(NodeId target,
                                com.raftkv.raft.RequestVoteRequest req,
                                BiConsumer<NodeId, com.raftkv.raft.RequestVoteResponse> callback) {
        ManagedChannel raw = channels.get(target);
        if (raw == null) {
            return; // no channel for this peer — silently drop
        }
        Channel channel = applyInterceptors(raw);
        RaftServiceGrpc.RaftServiceStub stub = RaftServiceGrpc.newStub(channel);

        RequestVoteRequest protoReq = RequestVoteRequest.newBuilder()
                .setTerm(req.getTerm())
                .setCandidateId(req.getCandidateId().getId())
                .setLastLogIndex(req.getLastLogIndex())
                .setLastLogTerm(req.getLastLogTerm())
                .build();

        stub.requestVote(protoReq, new StreamObserver<RequestVoteResponse>() {
            @Override
            public void onNext(RequestVoteResponse resp) {
                com.raftkv.raft.RequestVoteResponse raftResp =
                        new com.raftkv.raft.RequestVoteResponse(resp.getTerm(), resp.getVoteGranted());
                eventLoop.execute(() -> callback.accept(target, raftResp));
            }

            @Override
            public void onError(Throwable t) {
                // Drop on error — Raft handles missing responses via election timeout
            }

            @Override
            public void onCompleted() {}
        });
    }

    @Override
    public void sendAppendEntries(NodeId target,
                                  com.raftkv.raft.AppendEntriesRequest req,
                                  BiConsumer<NodeId, com.raftkv.raft.AppendEntriesResponse> callback) {
        ManagedChannel raw = channels.get(target);
        if (raw == null) {
            return;
        }
        Channel channel = applyInterceptors(raw);
        RaftServiceGrpc.RaftServiceStub stub = RaftServiceGrpc.newStub(channel);

        AppendEntriesRequest.Builder builder = AppendEntriesRequest.newBuilder()
                .setTerm(req.getTerm())
                .setLeaderId(req.getLeaderId().getId())
                .setPrevLogIndex(req.getPrevLogIndex())
                .setPrevLogTerm(req.getPrevLogTerm())
                .setLeaderCommit(req.getLeaderCommit());

        for (LogEntry entry : req.getEntries()) {
            builder.addEntries(LogEntryProto.newBuilder()
                    .setTerm(entry.getTerm())
                    .setIndex(entry.getIndex())
                    .setCommand(ByteString.copyFrom(entry.getCommand()))
                    .build());
        }

        stub.appendEntries(builder.build(), new StreamObserver<AppendEntriesResponse>() {
            @Override
            public void onNext(AppendEntriesResponse resp) {
                com.raftkv.raft.AppendEntriesResponse raftResp =
                        new com.raftkv.raft.AppendEntriesResponse(
                                resp.getTerm(), resp.getSuccess(), resp.getConflictIndex());
                eventLoop.execute(() -> callback.accept(target, raftResp));
            }

            @Override
            public void onError(Throwable t) {
                // Drop on error
            }

            @Override
            public void onCompleted() {}
        });
    }

    private Channel applyInterceptors(ManagedChannel raw) {
        if (interceptors.isEmpty()) {
            return raw;
        }
        return ClientInterceptors.intercept(raw, interceptors);
    }
}
