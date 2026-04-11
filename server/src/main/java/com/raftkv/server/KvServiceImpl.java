package com.raftkv.server;

import com.google.protobuf.ByteString;
import com.raftkv.proto.DeleteRequest;
import com.raftkv.proto.DeleteResponse;
import com.raftkv.proto.GetNodeRoleRequest;
import com.raftkv.proto.GetNodeRoleResponse;
import com.raftkv.proto.GetRequest;
import com.raftkv.proto.GetResponse;
import com.raftkv.proto.KvServiceGrpc;
import com.raftkv.proto.PutRequest;
import com.raftkv.proto.PutResponse;
import com.raftkv.proto.Status;
import com.raftkv.raft.RaftNode;
import com.raftkv.store.KVStore;
import com.raftkv.store.RoutingResult;
import com.raftkv.store.ShardRouter;
import io.grpc.stub.StreamObserver;

import java.util.Optional;

/**
 * gRPC service implementation for client-facing KV operations.
 *
 * <p>Each operation routes via {@link ShardRouter} to determine if this node is the
 * leader for the target shard. Writes are rejected with NOT_LEADER when the router
 * does not grant ownership to the local node.
 */
public class KvServiceImpl extends KvServiceGrpc.KvServiceImplBase {

    private final KVStore kvStore;
    private final ShardRouter shardRouter;
    private final RaftNode raftNode;

    public KvServiceImpl(KVStore kvStore, ShardRouter shardRouter, RaftNode raftNode) {
        this.kvStore = kvStore;
        this.shardRouter = shardRouter;
        this.raftNode = raftNode;
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        String key = request.getKey();
        RoutingResult routing = shardRouter.route(key, true);

        PutResponse response;
        if (routing instanceof RoutingResult.Resharding) {
            response = PutResponse.newBuilder()
                    .setStatus(Status.RESHARDING)
                    .build();
        } else if (routing instanceof RoutingResult.NotLeader notLeader) {
            String hint = notLeader.hint().map(Object::toString).orElse("");
            response = PutResponse.newBuilder()
                    .setStatus(Status.NOT_LEADER)
                    .setLeaderHint(hint)
                    .build();
        } else {
            // RoutingResult.Ok — this node is the leader
            String value = request.getValue().toStringUtf8();
            kvStore.put(key, value);
            response = PutResponse.newBuilder()
                    .setStatus(Status.OK)
                    .build();
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        String key = request.getKey();
        RoutingResult routing = shardRouter.route(key, false);

        GetResponse response;
        if (routing instanceof RoutingResult.NotLeader notLeader) {
            String hint = notLeader.hint().map(Object::toString).orElse("");
            response = GetResponse.newBuilder()
                    .setStatus(Status.NOT_LEADER)
                    .setLeaderHint(hint)
                    .build();
        } else {
            // Ok or Resharding — attempt the read
            Optional<String> value = kvStore.get(key);
            if (value.isPresent()) {
                response = GetResponse.newBuilder()
                        .setStatus(Status.OK)
                        .setValue(ByteString.copyFromUtf8(value.get()))
                        .build();
            } else {
                response = GetResponse.newBuilder()
                        .setStatus(Status.KEY_NOT_FOUND)
                        .build();
            }
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        String key = request.getKey();
        RoutingResult routing = shardRouter.route(key, true);

        DeleteResponse response;
        if (routing instanceof RoutingResult.Resharding) {
            response = DeleteResponse.newBuilder()
                    .setStatus(Status.RESHARDING)
                    .build();
        } else if (routing instanceof RoutingResult.NotLeader notLeader) {
            String hint = notLeader.hint().map(Object::toString).orElse("");
            response = DeleteResponse.newBuilder()
                    .setStatus(Status.NOT_LEADER)
                    .setLeaderHint(hint)
                    .build();
        } else {
            // RoutingResult.Ok
            boolean removed = kvStore.delete(key);
            Status status = removed ? Status.OK : Status.KEY_NOT_FOUND;
            response = DeleteResponse.newBuilder()
                    .setStatus(status)
                    .build();
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getNodeRole(GetNodeRoleRequest request,
                            StreamObserver<GetNodeRoleResponse> responseObserver) {
        String role = raftNode.getRole().name();
        String nodeId = raftNode.getSelfId().getId();
        String leaderId = raftNode.getLeaderId() != null ? raftNode.getLeaderId().getId() : "";

        GetNodeRoleResponse response = GetNodeRoleResponse.newBuilder()
                .setRole(role)
                .setNodeId(nodeId)
                .setLeaderId(leaderId)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
