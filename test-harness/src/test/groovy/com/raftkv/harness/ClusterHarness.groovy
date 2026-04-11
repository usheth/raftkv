package com.raftkv.harness

import com.raftkv.core.Clock
import com.raftkv.core.NodeId
import com.raftkv.core.RaftRole
import com.raftkv.core.SystemClock
import com.raftkv.raft.AppendEntriesRequest
import com.raftkv.raft.AppendEntriesResponse
import com.raftkv.raft.PersistentState
import com.raftkv.raft.RaftNode
import com.raftkv.raft.RaftTransport
import com.raftkv.raft.RequestVoteRequest
import com.raftkv.raft.RequestVoteResponse
import com.raftkv.raft.StateMachineApplier
import com.raftkv.server.RaftServer
import com.raftkv.server.RaftServiceImpl
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer

/**
 * Integration test harness that manages a cluster of Raft nodes running in-process
 * over real gRPC connections.
 *
 * <p>Each node runs on an ephemeral port. Fault injection is achieved via a
 * {@link PartitionAwareTransport} wrapper that drops RPCs on partitioned links.
 *
 * <p>Usage pattern:
 * <pre>
 *   def harness = new ClusterHarness(3)
 *   try {
 *     NodeId leader = harness.awaitLeader(Duration.ofSeconds(5))
 *     ...
 *   } finally {
 *     harness.close()
 *   }
 * </pre>
 */
class ClusterHarness implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClusterHarness)

    /** State for one node in the cluster. */
    static class NodeState {
        final NodeId id
        final RaftNode raftNode
        final RaftServer raftServer
        final ScheduledExecutorService tickExecutor
        final ScheduledFuture<?> tickFuture
        final List<ManagedChannel> outboundChannels

        NodeState(NodeId id, RaftNode raftNode, RaftServer raftServer,
                  ScheduledExecutorService tickExecutor, ScheduledFuture<?> tickFuture,
                  List<ManagedChannel> outboundChannels) {
            this.id = id
            this.raftNode = raftNode
            this.raftServer = raftServer
            this.tickExecutor = tickExecutor
            this.tickFuture = tickFuture
            this.outboundChannels = outboundChannels
        }
    }

    /**
     * A transport that wraps gRPC channels but allows partitioning of specific links.
     * The partition state is looked up by (source, target) pair from a shared map.
     */
    static class PartitionAwareTransport implements RaftTransport {

        private final NodeId selfId
        private final Map<NodeId, ManagedChannel> channels
        // Key = "fromId->toId", value = partitioned flag
        private final Map<String, AtomicBoolean> partitionFlags
        private final ScheduledExecutorService eventLoop

        PartitionAwareTransport(NodeId selfId, Map<NodeId, ManagedChannel> channels,
                                Map<String, AtomicBoolean> partitionFlags,
                                ScheduledExecutorService eventLoop) {
            this.selfId = selfId
            this.channels = channels
            this.partitionFlags = partitionFlags
            this.eventLoop = eventLoop
        }

        private boolean isPartitioned(NodeId target) {
            String key = "${selfId.id}->${target.id}"
            AtomicBoolean flag = partitionFlags.get(key)
            return flag != null && flag.get()
        }

        @Override
        void sendRequestVote(NodeId target, RequestVoteRequest req,
                             BiConsumer<NodeId, RequestVoteResponse> callback) {
            if (isPartitioned(target)) return
            ManagedChannel channel = channels.get(target)
            if (channel == null) return
            def stub = com.raftkv.proto.RaftServiceGrpc.newStub(channel)
            def protoReq = com.raftkv.proto.RequestVoteRequest.newBuilder()
                    .setTerm(req.getTerm())
                    .setCandidateId(req.getCandidateId().getId())
                    .setLastLogIndex(req.getLastLogIndex())
                    .setLastLogTerm(req.getLastLogTerm())
                    .build()
            stub.requestVote(protoReq, new io.grpc.stub.StreamObserver<com.raftkv.proto.RequestVoteResponse>() {
                @Override
                void onNext(com.raftkv.proto.RequestVoteResponse resp) {
                    def raftResp = new RequestVoteResponse(resp.getTerm(), resp.getVoteGranted())
                    eventLoop.execute { callback.accept(target, raftResp) }
                }
                @Override
                void onError(Throwable t) { /* drop */ }
                @Override
                void onCompleted() { /* no-op */ }
            })
        }

        @Override
        void sendAppendEntries(NodeId target, AppendEntriesRequest req,
                               BiConsumer<NodeId, AppendEntriesResponse> callback) {
            if (isPartitioned(target)) return
            ManagedChannel channel = channels.get(target)
            if (channel == null) return
            def stub = com.raftkv.proto.RaftServiceGrpc.newStub(channel)
            def builder = com.raftkv.proto.AppendEntriesRequest.newBuilder()
                    .setTerm(req.getTerm())
                    .setLeaderId(req.getLeaderId().getId())
                    .setPrevLogIndex(req.getPrevLogIndex())
                    .setPrevLogTerm(req.getPrevLogTerm())
                    .setLeaderCommit(req.getLeaderCommit())
            for (def entry : req.getEntries()) {
                builder.addEntries(com.raftkv.proto.LogEntryProto.newBuilder()
                        .setTerm(entry.getTerm())
                        .setIndex(entry.getIndex())
                        .setCommand(com.google.protobuf.ByteString.copyFrom(entry.getCommand()))
                        .build())
            }
            stub.appendEntries(builder.build(), new io.grpc.stub.StreamObserver<com.raftkv.proto.AppendEntriesResponse>() {
                @Override
                void onNext(com.raftkv.proto.AppendEntriesResponse resp) {
                    def raftResp = new AppendEntriesResponse(resp.getTerm(), resp.getSuccess(), resp.getConflictIndex())
                    eventLoop.execute { callback.accept(target, raftResp) }
                }
                @Override
                void onError(Throwable t) { /* drop */ }
                @Override
                void onCompleted() { /* no-op */ }
            })
        }
    }

    // -------------------------------------------------------------------------

    private final List<NodeState> nodes = []
    private final List<NodeId> nodeIds = []
    // Key = "fromId->toId"
    private final Map<String, AtomicBoolean> partitionFlags = new ConcurrentHashMap<>()
    private final Path tempDir

    /**
     * Creates and starts a cluster of {@code nodeCount} nodes.
     *
     * @param nodeCount number of nodes to create
     * @param clockFactory factory that returns a Clock for each node (by index); defaults to SystemClock
     */
    ClusterHarness(int nodeCount, Closure<Clock> clockFactory = { int idx -> new SystemClock() }) {
        tempDir = Files.createTempDirectory("raftkv-harness-")

        // Build NodeIds
        nodeCount.times { int i ->
            nodeIds << new NodeId("node-${i}")
        }

        // Two-pass: first create all servers to get ports, then wire transports

        // Pass 1: create RaftNodes with placeholder transports, start servers
        def raftNodes = new RaftNode[nodeCount]
        def raftServers = new RaftServer[nodeCount]
        def eventLoops = new ScheduledExecutorService[nodeCount]
        def transportRefs = new PartitionAwareTransport[nodeCount]

        // We need a way to defer channel creation until all ports are known.
        // Strategy: start servers, get ports, then build channels, build transports,
        // and inject transports into RaftNodes via a transport holder.

        // Use a TransportHolder so RaftNode can forward calls before transport is set
        def holders = (0..<nodeCount).collect { new TransportHolder() }

        nodeCount.times { int i ->
            NodeId selfId = nodeIds[i]
            List<NodeId> peers = nodeIds.findAll { it != selfId }
            Clock clock = clockFactory(i)

            Path stateFile = tempDir.resolve("node-${i}.raft")
            PersistentState storage = new PersistentState(stateFile)
            StateMachineApplier applier = { entry -> /* no-op state machine */ } as StateMachineApplier

            def eventLoop = Executors.newSingleThreadScheduledExecutor { r ->
                def t = new Thread(r, "raft-node-${i}")
                t.daemon = true
                t
            }
            eventLoops[i] = eventLoop

            RaftNode raftNode = new RaftNode(selfId, peers, storage, clock, holders[i], applier)
            raftNodes[i] = raftNode

            RaftServiceImpl raftService = new RaftServiceImpl(raftNode, eventLoop)
            RaftServer server = new RaftServer(0, [raftService])
            server.start()
            raftServers[i] = server
        }

        // Pass 2: build channels now that all ports are known, wire transports
        def channelsByNode = new Map[nodeCount]
        nodeCount.times { int i ->
            def channels = new HashMap<NodeId, ManagedChannel>()
            nodeCount.times { int j ->
                if (i != j) {
                    int port = raftServers[j].getPort()
                    ManagedChannel ch = ManagedChannelBuilder
                            .forAddress("localhost", port)
                            .usePlaintext()
                            .build()
                    channels[nodeIds[j]] = ch
                }
            }
            channelsByNode[i] = channels
        }

        // Pre-populate partition flags for all directed links (initially not partitioned)
        nodeCount.times { int i ->
            nodeCount.times { int j ->
                if (i != j) {
                    String key = "${nodeIds[i].id}->${nodeIds[j].id}"
                    partitionFlags[key] = new AtomicBoolean(false)
                }
            }
        }

        // Wire transports into holders and start tick loops
        nodeCount.times { int i ->
            NodeId selfId = nodeIds[i]
            def transport = new PartitionAwareTransport(
                    selfId,
                    channelsByNode[i] as Map<NodeId, ManagedChannel>,
                    partitionFlags,
                    eventLoops[i])
            transportRefs[i] = transport
            holders[i].delegate = transport

            // Start the Raft node on the event loop
            eventLoops[i].execute { raftNodes[i].start() }

            // Tick every 10 ms
            def tickFuture = eventLoops[i].scheduleAtFixedRate(
                    { raftNodes[i].tick() } as Runnable,
                    10, 10, TimeUnit.MILLISECONDS)

            nodes << new NodeState(
                    selfId,
                    raftNodes[i],
                    raftServers[i],
                    eventLoops[i],
                    tickFuture,
                    (channelsByNode[i] as Map<NodeId, ManagedChannel>).values().toList()
            )
        }

        log.info("ClusterHarness started with {} nodes", nodeCount)
    }

    /**
     * Installs a bidirectional partition between nodes a and b.
     * Messages in both directions are silently dropped.
     */
    void partition(NodeId a, NodeId b) {
        setPartition(a, b, true)
        setPartition(b, a, true)
        log.info("Partitioned: {} <-> {}", a, b)
    }

    /**
     * Removes the partition between nodes a and b.
     */
    void heal(NodeId a, NodeId b) {
        setPartition(a, b, false)
        setPartition(b, a, false)
        log.info("Healed partition: {} <-> {}", a, b)
    }

    private void setPartition(NodeId from, NodeId to, boolean partitioned) {
        String key = "${from.id}->${to.id}"
        AtomicBoolean flag = partitionFlags.get(key)
        if (flag != null) {
            flag.set(partitioned)
        }
    }

    /**
     * Returns the NodeId at the given index (0-based).
     */
    NodeId nodeId(int index) {
        return nodeIds[index]
    }

    /**
     * Returns all NodeIds in the cluster.
     */
    List<NodeId> allNodeIds() {
        return Collections.unmodifiableList(nodeIds)
    }

    /**
     * Returns the RaftNode for the given NodeId.
     */
    RaftNode raftNode(NodeId id) {
        nodes.find { it.id == id }?.raftNode
    }

    /**
     * Polls until exactly one LEADER exists in the cluster, then returns its NodeId.
     *
     * @param timeout maximum wait time
     * @return the leader NodeId
     * @throws IllegalStateException if no leader is elected within the timeout
     */
    NodeId awaitLeader(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            def leaders = nodes.findAll { it.raftNode.getRole() == RaftRole.LEADER }
            // Wait for exactly one leader AND all other nodes to be followers —
            // this avoids returning a stale leader that is about to be replaced.
            if (leaders.size() == 1) {
                NodeId leaderId = leaders[0].id
                boolean allFollowers = nodes
                        .findAll { it.id != leaderId }
                        .every { it.raftNode.getRole() == RaftRole.FOLLOWER }
                if (allFollowers) {
                    log.info("Leader elected and cluster stable: {}", leaderId)
                    return leaderId
                }
            }
            Thread.sleep(20)
        }
        def roles = nodes.collect { "${it.id}=${it.raftNode.getRole()}" }
        throw new IllegalStateException("No stable leader elected within ${timeout}. Roles: ${roles}")
    }

    /**
     * Polls until all nodes have a commitIndex of at least {@code index}.
     *
     * @param index  the commit index to wait for
     * @param timeout maximum wait time
     * @throws IllegalStateException if convergence is not achieved within the timeout
     */
    void awaitCommitIndex(long index, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            boolean allCommitted = nodes.every { it.raftNode.getCommitIndex() >= index }
            if (allCommitted) {
                log.info("All nodes committed index {}", index)
                return
            }
            Thread.sleep(20)
        }
        def commits = nodes.collect { "${it.id}=commitIndex:${it.raftNode.getCommitIndex()}" }
        throw new IllegalStateException("Not all nodes committed index ${index} within ${timeout}. State: ${commits}")
    }

    /**
     * Proposes a log entry on the given leader node.
     * Must be called with the current leader's NodeId.
     */
    void propose(NodeId leaderId, byte[] command) {
        NodeState nodeState = nodes.find { it.id == leaderId }
        if (nodeState == null) throw new IllegalArgumentException("Unknown node: ${leaderId}")
        // Submit on the node's event loop to maintain single-threaded access
        nodeState.tickExecutor.execute { nodeState.raftNode.appendEntry(command) }
    }

    @Override
    void close() {
        log.info("Closing ClusterHarness")
        nodes.each { node ->
            try {
                node.tickFuture.cancel(false)
                node.tickExecutor.shutdown()
                node.raftServer.stop()
                node.outboundChannels.each { ch ->
                    try { ch.shutdownNow() } catch (Exception ignored) {}
                }
                node.tickExecutor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (Exception e) {
                log.warn("Error closing node {}", node.id, e)
            }
        }
        // Clean up temp files
        try {
            tempDir.toFile().deleteDir()
        } catch (Exception ignored) {}
        log.info("ClusterHarness closed")
    }

    // -------------------------------------------------------------------------
    // Internal: transport holder for two-phase construction
    // -------------------------------------------------------------------------

    /**
     * Mutable wrapper around a RaftTransport delegate.
     * Allows nodes to be constructed before their transport is wired up.
     */
    static class TransportHolder implements RaftTransport {
        volatile RaftTransport delegate

        @Override
        void sendRequestVote(NodeId target, RequestVoteRequest req,
                             BiConsumer<NodeId, RequestVoteResponse> callback) {
            delegate?.sendRequestVote(target, req, callback)
        }

        @Override
        void sendAppendEntries(NodeId target, AppendEntriesRequest req,
                               BiConsumer<NodeId, AppendEntriesResponse> callback) {
            delegate?.sendAppendEntries(target, req, callback)
        }
    }
}
