package com.raftkv.raft;

import com.raftkv.core.Clock;
import com.raftkv.core.NodeId;
import com.raftkv.core.RaftRole;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Random;

/**
 * Core Raft state machine.
 *
 * <p>This class is intentionally <em>not</em> thread-safe and contains no threading of its
 * own. It is driven by an external single-threaded event loop that calls {@link #tick()}
 * periodically and routes incoming RPCs to the appropriate handler methods.
 *
 * <h2>Election timeout</h2>
 * <ul>
 *   <li>On {@link #start()} the node becomes a FOLLOWER with a randomised election deadline.</li>
 *   <li>{@link #tick()} checks whether the deadline has passed; if it has and the node is not a
 *       LEADER it starts a new election.</li>
 *   <li>Randomised timeout: 150–300 ms (see Raft §5.2).</li>
 * </ul>
 */
public final class RaftNode {

    private static final int ELECTION_TIMEOUT_MIN_MS = 150;
    private static final int ELECTION_TIMEOUT_MAX_MS = 300;

    // ---- dependencies -------------------------------------------------------
    private final NodeId selfId;
    private final List<NodeId> peers;      // all other nodes (does NOT include self)
    private final PersistentState storage;
    private final Clock clock;
    private final RaftTransport transport;
    private final StateMachineApplier applier;
    private final Random random;

    // ---- volatile state (Raft §5.1) ----------------------------------------
    private long commitIndex = 0;
    private long lastApplied = 0;

    // ---- server state -------------------------------------------------------
    private RaftRole role = RaftRole.FOLLOWER;
    private NodeId currentLeaderId = null;
    private int votesGranted = 0;
    private long electionDeadline = 0;

    // -------------------------------------------------------------------------

    /**
     * Creates a new RaftNode.
     *
     * @param selfId    this node's identifier
     * @param peers     the identifiers of all <em>other</em> nodes in the cluster
     * @param storage   persistent state (currentTerm, votedFor, log)
     * @param clock     injectable clock for deterministic testing
     * @param transport outbound RPC transport
     * @param applier   state machine applier, called on commit
     */
    public RaftNode(NodeId selfId, List<NodeId> peers, PersistentState storage,
                    Clock clock, RaftTransport transport, StateMachineApplier applier) {
        this.selfId = selfId;
        this.peers = List.copyOf(peers);
        this.storage = storage;
        this.clock = clock;
        this.transport = transport;
        this.applier = applier;
        this.random = new Random();
    }

    /**
     * Constructor that also accepts a {@link Random} for reproducible tests.
     */
    public RaftNode(NodeId selfId, List<NodeId> peers, PersistentState storage,
                    Clock clock, RaftTransport transport, StateMachineApplier applier,
                    Random random) {
        this.selfId = selfId;
        this.peers = List.copyOf(peers);
        this.storage = storage;
        this.clock = clock;
        this.transport = transport;
        this.applier = applier;
        this.random = random;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialises node state and sets the initial election deadline.
     * Must be called once before {@link #tick()} or any RPC handler.
     */
    public void start() {
        role = RaftRole.FOLLOWER;
        currentLeaderId = null;
        votesGranted = 0;
        resetElectionDeadline();
    }

    // -------------------------------------------------------------------------
    // Timer tick — called periodically by the event loop
    // -------------------------------------------------------------------------

    /**
     * Called periodically by the external event loop. Checks whether the election timeout
     * has elapsed and starts a new election if needed.
     */
    public void tick() {
        if (role != RaftRole.LEADER && clock.currentTimeMillis() >= electionDeadline) {
            startElection();
        }
    }

    // -------------------------------------------------------------------------
    // RPC handlers
    // -------------------------------------------------------------------------

    /**
     * Handles an incoming RequestVote RPC.
     *
     * @param req the request from a candidate
     * @return    the response to send back
     */
    public RequestVoteResponse handleRequestVote(RequestVoteRequest req) {
        long myTerm = storage.getCurrentTerm();

        // If we see a higher term, convert to follower first.
        if (req.getTerm() > myTerm) {
            becomeFollower(req.getTerm(), null);
            myTerm = storage.getCurrentTerm();
        }

        // Deny if request term is stale.
        if (req.getTerm() < myTerm) {
            return new RequestVoteResponse(myTerm, false);
        }

        // Check if we can grant the vote.
        NodeId votedFor = storage.getVotedFor();
        boolean canVote = (votedFor == null || votedFor.equals(req.getCandidateId()));

        // Candidate log must be at least as up-to-date as ours (Raft §5.4.1).
        boolean candidateLogOk = isCandidateLogUpToDate(req.getLastLogTerm(), req.getLastLogIndex());

        if (canVote && candidateLogOk) {
            persist(myTerm, req.getCandidateId());
            resetElectionDeadline(); // we granted a vote, reset our timeout
            return new RequestVoteResponse(myTerm, true);
        }

        return new RequestVoteResponse(myTerm, false);
    }

    /**
     * Handles a RequestVote response received from {@code from}.
     *
     * @param from the node that responded
     * @param resp the response
     */
    public void handleRequestVoteResponse(NodeId from, RequestVoteResponse resp) {
        long myTerm = storage.getCurrentTerm();

        // Higher term in response: we are behind, revert to follower.
        if (resp.getTerm() > myTerm) {
            becomeFollower(resp.getTerm(), null);
            return;
        }

        // Ignore stale responses (e.g., from a previous election term).
        if (resp.getTerm() < myTerm || role != RaftRole.CANDIDATE) {
            return;
        }

        if (resp.isVoteGranted()) {
            votesGranted++;
            if (hasQuorum(votesGranted)) {
                becomeLeader();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public RaftRole getRole() {
        return role;
    }

    public NodeId getLeaderId() {
        return currentLeaderId;
    }

    public NodeId getSelfId() {
        return selfId;
    }

    public long getCurrentTerm() {
        return storage.getCurrentTerm();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void startElection() {
        long newTerm = storage.getCurrentTerm() + 1;
        role = RaftRole.CANDIDATE;
        currentLeaderId = null;
        // Vote for ourselves — counts as 1.
        votesGranted = 1;
        persist(newTerm, selfId);
        resetElectionDeadline();

        // In a single-node cluster we already have a quorum — become leader immediately.
        if (hasQuorum(votesGranted)) {
            becomeLeader();
            return;
        }

        RequestVoteRequest req = new RequestVoteRequest(
                newTerm, selfId, storage.lastLogIndex(), storage.lastLogTerm());

        for (NodeId peer : peers) {
            transport.sendRequestVote(peer, req, this::handleRequestVoteResponse);
        }
    }

    private void becomeLeader() {
        role = RaftRole.LEADER;
        currentLeaderId = selfId;
        // Future: initialise nextIndex / matchIndex and send initial heartbeats.
    }

    private void becomeFollower(long term, NodeId leaderId) {
        role = RaftRole.FOLLOWER;
        currentLeaderId = leaderId;
        votesGranted = 0;
        persist(term, null);
        resetElectionDeadline();
    }

    /**
     * Returns true when the number of votes represents a majority of the cluster
     * (self + peers).
     */
    private boolean hasQuorum(int votes) {
        int clusterSize = 1 + peers.size(); // self + peers
        return votes > clusterSize / 2;
    }

    /**
     * Returns true if the candidate's log is at least as up-to-date as ours
     * (Raft §5.4.1).
     */
    private boolean isCandidateLogUpToDate(long candidateLastLogTerm, long candidateLastLogIndex) {
        long myLastTerm = storage.lastLogTerm();
        long myLastIndex = storage.lastLogIndex();
        if (candidateLastLogTerm != myLastTerm) {
            return candidateLastLogTerm > myLastTerm;
        }
        return candidateLastLogIndex >= myLastIndex;
    }

    private void resetElectionDeadline() {
        int range = ELECTION_TIMEOUT_MAX_MS - ELECTION_TIMEOUT_MIN_MS;
        long timeout = ELECTION_TIMEOUT_MIN_MS + (random.nextInt(range + 1));
        electionDeadline = clock.currentTimeMillis() + timeout;
    }

    private void persist(long term, NodeId votedFor) {
        try {
            storage.setTermAndVote(term, votedFor);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist Raft state", e);
        }
    }
}
