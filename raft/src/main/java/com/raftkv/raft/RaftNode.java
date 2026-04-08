package com.raftkv.raft;

import com.raftkv.core.Clock;
import com.raftkv.core.LogEntry;
import com.raftkv.core.NodeId;
import com.raftkv.core.RaftRole;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

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
    private static final int HEARTBEAT_INTERVAL_MS = 50;
    private static final int REPLICATION_BATCH_SIZE = 50;

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

    // ---- leader-only replication state -------------------------------------
    /** nextIndex[peer] — index of next log entry to send to each peer. */
    private final Map<NodeId, Long> nextIndex = new HashMap<>();
    /** matchIndex[peer] — highest log entry known to be replicated on peer. */
    private final Map<NodeId, Long> matchIndex = new HashMap<>();
    /** When the leader last sent heartbeats. */
    private long lastHeartbeatSent = 0;

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
     * has elapsed and starts a new election if needed. If LEADER, sends heartbeats when
     * the heartbeat interval has elapsed.
     */
    public void tick() {
        if (role == RaftRole.LEADER) {
            long now = clock.currentTimeMillis();
            if (now - lastHeartbeatSent >= HEARTBEAT_INTERVAL_MS) {
                sendHeartbeats();
                lastHeartbeatSent = now;
            }
        } else if (clock.currentTimeMillis() >= electionDeadline) {
            startElection();
        }
    }

    // -------------------------------------------------------------------------
    // Client-facing entry proposal
    // -------------------------------------------------------------------------

    /**
     * Proposes a new log entry (only valid when this node is LEADER).
     * Appends the entry to the local log and immediately replicates to all peers.
     *
     * @param command the raw bytes of the command to be applied to the state machine
     * @throws IllegalStateException if the node is not currently the LEADER
     */
    public void appendEntry(byte[] command) {
        if (role != RaftRole.LEADER) {
            throw new IllegalStateException("Not the leader");
        }
        long newIndex = storage.lastLogIndex() + 1;
        LogEntry entry = new LogEntry(storage.getCurrentTerm(), newIndex, command);
        try {
            storage.appendEntry(entry);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append log entry", e);
        }
        // Replicate to all peers
        for (NodeId peer : peers) {
            replicateToPeer(peer);
        }
    }

    // -------------------------------------------------------------------------
    // Replication
    // -------------------------------------------------------------------------

    /**
     * Sends an AppendEntries RPC to the given peer using the current nextIndex for that peer.
     */
    public void replicateToPeer(NodeId peer) {
        long ni = nextIndex.getOrDefault(peer, storage.lastLogIndex() + 1);
        long prevLogIndex = ni - 1;
        long prevLogTerm = 0;
        if (prevLogIndex > 0) {
            LogEntry prevEntry = storage.getEntryAtIndex(prevLogIndex);
            prevLogTerm = prevEntry != null ? prevEntry.getTerm() : 0;
        }

        List<LogEntry> entries = storage.getEntriesFrom(ni);
        if (entries.size() > REPLICATION_BATCH_SIZE) {
            entries = entries.subList(0, REPLICATION_BATCH_SIZE);
        }

        AppendEntriesRequest req = new AppendEntriesRequest(
                storage.getCurrentTerm(), selfId, prevLogIndex, prevLogTerm,
                entries, commitIndex);

        transport.sendAppendEntries(peer, req, (from, resp) ->
                handleAppendEntriesResponse(from, req, resp));
    }

    // -------------------------------------------------------------------------
    // RPC handlers
    // -------------------------------------------------------------------------

    /**
     * Handles an incoming AppendEntries RPC from the leader (Raft §5.3).
     *
     * @param req   the request from the leader
     * @param reply callback to send the response back
     */
    public void handleAppendEntries(AppendEntriesRequest req, Consumer<AppendEntriesResponse> reply) {
        long myTerm = storage.getCurrentTerm();

        // Rule 1: reply false if term < currentTerm
        if (req.getTerm() < myTerm) {
            reply.accept(new AppendEntriesResponse(myTerm, false));
            return;
        }

        // If we see a higher term, or same-term message, convert to follower
        if (req.getTerm() > myTerm) {
            becomeFollower(req.getTerm(), req.getLeaderId());
            myTerm = storage.getCurrentTerm();
        } else {
            // Same term — update leader and reset timeout (heartbeat)
            currentLeaderId = req.getLeaderId();
            role = RaftRole.FOLLOWER;
            resetElectionDeadline();
        }

        // Rule 2: check log consistency at prevLogIndex/prevLogTerm
        long prevLogIndex = req.getPrevLogIndex();
        if (prevLogIndex > 0) {
            LogEntry prevEntry = storage.getEntryAtIndex(prevLogIndex);
            if (prevEntry == null) {
                // We don't have the entry at prevLogIndex
                long conflictIndex = storage.lastLogIndex() + 1;
                reply.accept(new AppendEntriesResponse(myTerm, false, conflictIndex));
                return;
            }
            if (prevEntry.getTerm() != req.getPrevLogTerm()) {
                // Term conflict — back up to start of conflicting term
                long conflictIndex = prevLogIndex;
                // Walk back to find the first index with the conflicting term
                long conflictTerm = prevEntry.getTerm();
                for (long i = prevLogIndex - 1; i >= 1; i--) {
                    LogEntry e = storage.getEntryAtIndex(i);
                    if (e == null || e.getTerm() != conflictTerm) {
                        conflictIndex = i + 1;
                        break;
                    }
                    if (i == 1) conflictIndex = 1;
                }
                reply.accept(new AppendEntriesResponse(myTerm, false, conflictIndex));
                return;
            }
        }

        // Rule 3 & 4: append new entries (truncating conflicting suffix)
        List<LogEntry> newEntries = req.getEntries();
        for (int i = 0; i < newEntries.size(); i++) {
            LogEntry incoming = newEntries.get(i);
            LogEntry existing = storage.getEntryAtIndex(incoming.getIndex());
            if (existing != null && existing.getTerm() != incoming.getTerm()) {
                // Conflict — truncate from this point
                try {
                    storage.truncateFrom(incoming.getIndex());
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to truncate log", e);
                }
                existing = null;
            }
            if (existing == null) {
                // Append from here onward
                for (int j = i; j < newEntries.size(); j++) {
                    try {
                        storage.appendEntry(newEntries.get(j));
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to append entry", e);
                    }
                }
                break;
            }
        }

        // Rule 5: advance commitIndex
        if (req.getLeaderCommit() > commitIndex) {
            commitIndex = Math.min(req.getLeaderCommit(), storage.lastLogIndex());
            applyCommitted();
        }

        reply.accept(new AppendEntriesResponse(myTerm, true));
    }

    /**
     * Handles an AppendEntries response received from a peer (leader only).
     *
     * @param from    the peer that sent the response
     * @param sentReq the original request we sent
     * @param resp    the response
     */
    public void handleAppendEntriesResponse(NodeId from, AppendEntriesRequest sentReq,
                                             AppendEntriesResponse resp) {
        long myTerm = storage.getCurrentTerm();

        // Stale response for a term we've moved past — ignore
        if (resp.getTerm() > myTerm) {
            becomeFollower(resp.getTerm(), null);
            return;
        }

        // Only process if we're still the leader in the same term
        if (role != RaftRole.LEADER || resp.getTerm() < myTerm) {
            return;
        }

        if (resp.isSuccess()) {
            // Update matchIndex and nextIndex for this peer
            long newMatchIndex = sentReq.getPrevLogIndex() + sentReq.getEntries().size();
            long currentMatch = matchIndex.getOrDefault(from, 0L);
            if (newMatchIndex > currentMatch) {
                matchIndex.put(from, newMatchIndex);
            }
            nextIndex.put(from, matchIndex.getOrDefault(from, 0L) + 1);

            // Check if we can advance commitIndex
            advanceCommitIndex();
        } else {
            // Failure: use conflictIndex hint to back up nextIndex
            long conflictIdx = resp.getConflictIndex();
            long currentNext = nextIndex.getOrDefault(from, storage.lastLogIndex() + 1);
            if (conflictIdx > 0) {
                nextIndex.put(from, Math.min(conflictIdx, currentNext - 1));
            } else {
                nextIndex.put(from, Math.max(1, currentNext - 1));
            }
            // Retry replication with the backed-up index
            replicateToPeer(from);
        }
    }

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

    public long getCommitIndex() {
        return commitIndex;
    }

    public long getLastApplied() {
        return lastApplied;
    }

    public long getMatchIndex(NodeId peer) {
        return matchIndex.getOrDefault(peer, 0L);
    }

    public long getNextIndex(NodeId peer) {
        return nextIndex.getOrDefault(peer, storage.lastLogIndex() + 1);
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
        lastHeartbeatSent = clock.currentTimeMillis();

        // Initialise per-peer replication state
        long lastIdx = storage.lastLogIndex();
        for (NodeId peer : peers) {
            nextIndex.put(peer, lastIdx + 1);
            matchIndex.put(peer, 0L);
        }

        // Send initial heartbeats immediately
        sendHeartbeats();
    }

    private void becomeFollower(long term, NodeId leaderId) {
        role = RaftRole.FOLLOWER;
        currentLeaderId = leaderId;
        votesGranted = 0;
        persist(term, null);
        resetElectionDeadline();
    }

    private void sendHeartbeats() {
        for (NodeId peer : peers) {
            replicateToPeer(peer);
        }
    }

    /**
     * Checks if a new commitIndex can be established based on matchIndex values.
     * Only commits entries from the current term (Raft §5.4.2).
     */
    private void advanceCommitIndex() {
        long lastIdx = storage.lastLogIndex();
        // Try to advance commitIndex to the highest index that has majority replication
        for (long n = lastIdx; n > commitIndex; n--) {
            LogEntry entry = storage.getEntryAtIndex(n);
            if (entry == null) continue;
            // Only commit entries from current term (§5.4.2 safety rule)
            if (entry.getTerm() != storage.getCurrentTerm()) continue;

            // Count how many peers have replicated this index (including self)
            int count = 1; // self
            for (NodeId peer : peers) {
                if (matchIndex.getOrDefault(peer, 0L) >= n) {
                    count++;
                }
            }
            if (hasQuorum(count)) {
                commitIndex = n;
                applyCommitted();
                break;
            }
        }
    }

    /**
     * Applies all committed but not-yet-applied entries to the state machine.
     */
    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = storage.getEntryAtIndex(lastApplied);
            if (entry != null) {
                applier.apply(entry);
            }
        }
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
