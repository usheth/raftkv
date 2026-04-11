package com.raftkv.server;

import com.raftkv.core.LogEntry;
import com.raftkv.core.NodeId;
import com.raftkv.proto.AppendEntriesRequest;
import com.raftkv.proto.AppendEntriesResponse;
import com.raftkv.proto.LogEntryProto;
import com.raftkv.proto.RaftServiceGrpc;
import com.raftkv.proto.RequestVoteRequest;
import com.raftkv.proto.RequestVoteResponse;
import com.raftkv.raft.RaftNode;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * gRPC service implementation for node-to-node Raft RPCs.
 *
 * <p>Incoming gRPC calls are forwarded to the {@link RaftNode} event loop executor
 * so that all Raft state mutations happen on a single thread.
 */
public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private final RaftNode raftNode;
    private final Executor eventLoop;

    public RaftServiceImpl(RaftNode raftNode, Executor eventLoop) {
        this.raftNode = raftNode;
        this.eventLoop = eventLoop;
    }

    @Override
    public void requestVote(RequestVoteRequest request,
                            StreamObserver<RequestVoteResponse> responseObserver) {
        eventLoop.execute(() -> {
            com.raftkv.raft.RequestVoteRequest raftReq = new com.raftkv.raft.RequestVoteRequest(
                    request.getTerm(),
                    new NodeId(request.getCandidateId()),
                    request.getLastLogIndex(),
                    request.getLastLogTerm());

            com.raftkv.raft.RequestVoteResponse raftResp = raftNode.handleRequestVote(raftReq);

            RequestVoteResponse protoResp = RequestVoteResponse.newBuilder()
                    .setTerm(raftResp.getTerm())
                    .setVoteGranted(raftResp.isVoteGranted())
                    .build();

            responseObserver.onNext(protoResp);
            responseObserver.onCompleted();
        });
    }

    @Override
    public void appendEntries(AppendEntriesRequest request,
                              StreamObserver<AppendEntriesResponse> responseObserver) {
        eventLoop.execute(() -> {
            List<LogEntry> entries = new ArrayList<>();
            for (LogEntryProto proto : request.getEntriesList()) {
                entries.add(new LogEntry(proto.getTerm(), proto.getIndex(),
                        proto.getCommand().toByteArray()));
            }

            com.raftkv.raft.AppendEntriesRequest raftReq = new com.raftkv.raft.AppendEntriesRequest(
                    request.getTerm(),
                    new NodeId(request.getLeaderId()),
                    request.getPrevLogIndex(),
                    request.getPrevLogTerm(),
                    entries,
                    request.getLeaderCommit());

            raftNode.handleAppendEntries(raftReq, raftResp -> {
                AppendEntriesResponse protoResp = AppendEntriesResponse.newBuilder()
                        .setTerm(raftResp.getTerm())
                        .setSuccess(raftResp.isSuccess())
                        .setConflictIndex(raftResp.getConflictIndex())
                        .build();
                responseObserver.onNext(protoResp);
                responseObserver.onCompleted();
            });
        });
    }
}
