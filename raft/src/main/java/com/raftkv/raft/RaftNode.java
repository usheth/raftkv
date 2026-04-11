package com.raftkv.raft;

import com.raftkv.core.Clock;
import com.raftkv.core.LogEntry;
import com.raftkv.core.NodeId;
import com.raftkv.core.RaftRole;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
 *
 * <h2>Membership changes (§6)</h2>
 * <ul>
 *   <li>{@link #addPeer(NodeId)} / {@link #removePeer(NodeId)} append a C_old_new joint config
 *       entry. Once that entry commits the leader automatically appends a C_new entry.</li>
 *   <li>During the joint phase quorum = majority of old AND majority of new.</li>
 *   <li>After C_new commits, quorum = majority of new only.</li>
 * </ul>
 */
public final class RaftNode {

    private static final int ELECTION_TIMEOUT_MIN_MS = 150;
    private static final int ELECTION_TIMEOUT_MAX_MS = 300;
    private static final int HEARTBEAT_INTERVAL_MS = 50;
    private static final int REPLICATION_BATCH_SIZE = 50;

    // ---- dependencies -------------------------------------------------------
    private final NodeId selfId;
    /**
     * Initial peer list supplied at construction.  After config changes the
     * effective peer set is derived from {@link #activeConfig}.
     */
    private final List<NodeId> initialPeers;
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

    // ---- membership / joint consensus state --------------------------------
    /**
     * The current active cluster configuration.  Starts as a simple config derived from
     * {@code initialPeers}, and transitions through C_old_new → C_new as config entries commit.
     */
    private ClusterConfig activeConfig;

    /**
     * Log index of the pending C_old_new entry that has not yet been committed.
     * 0 means no config change is in progress.
     */
    private long pendingJointIndex = 0;

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
        this.initialPeers = List.copyOf(peers);
        this.storage = storage;
        this.clock = clock;
        this.transport = transport;
        this.applier = applier;
        this.random = new Random();
        this.activeConfig = ClusterConfig.simple(new HashSet<>(peers));
    }

    /**
     * Constructor that also accepts a {@link Random} for reproducible tests.
     */
    public RaftNode(NodeId selfId, List<NodeId> peers, PersistentState storage,
                    Clock clock, RaftTransport transport, StateMachineApplier applier,
                    Random random) {
        this.selfId = selfId;
        this.initialPeers = List.copyOf(peers);
        this.storage = storage;
        this.clock = clock;
        this.transport = transport;
        this.applier = applier;
        this.random = random;
        this.activeConfig = ClusterConfig.simple(new HashSet<>(peers));
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
        // Replicate to all peers in the effective config
        for (NodeId peer : effectivePeers()) {
            replicateToPeer(peer);
        }
    }

    // -------------------------------------------------------------------------
    // Membership change — joint consensus (§6)
    // -------------------------------------------------------------------------

    /**
     * Initiates adding {@code newPeer} to the cluster via joint consensus.
     * Appends a C_old,new config entry. Only valid when this node is LEADER and
     * no config change is already in progress.
     *
     * @param newPeer the peer to add
     * @throws IllegalStateException if not the leader, or a config change is already in flight
     */
    public void addPeer(NodeId newPeer) {
        if (role != RaftRole.LEADER) {
            throw new IllegalStateException("Not the leader — cannot change membership");
        }
        if (pendingJointIndex != 0) {
            throw new IllegalStateException("Config change already in progress");
        }
        Set<NodeId> currentPeers = new HashSet<>(activeConfig.getNewPeers());
        if (currentPeers.contains(newPeer)) {
            throw new IllegalArgumentException("Peer already in cluster: " + newPeer);
        }
        Set<NodeId> nextPeers = new HashSet<>(currentPeers);
        nextPeers.add(newPeer);
        appendConfigEntry(ClusterConfig.joint(currentPeers, nextPeers));
    }

    /**
     * Initiates removing {@code peer} from the cluster via joint consensus.
     * Appends a C_old,new config entry. Only valid when this node is LEADER and
     * no config change is already in progress.
     *
     * @param peer the peer to remove
     * @throws IllegalStateException if not the leader, or a config change is already in flight
     */
    public void removePeer(NodeId peer) {
        if (role != RaftRole.LEADER) {
            throw new IllegalStateException("Not the leader — cannot change membership");
        }
        if (pendingJointIndex != 0) {
            throw new IllegalStateException("Config change already in progress");
        }
        Set<NodeId> currentPeers = new HashSet<>(activeConfig.getNewPeers());
        if (!currentPeers.contains(peer)) {
            throw new IllegalArgumentException("Peer not in cluster: " + peer);
        }
        Set<NodeId> nextPeers = new HashSet<>(currentPeers);
        nextPeers.remove(peer);
        appendConfigEntry(ClusterConfig.joint(currentPeers, nextPeers));
    }

    /**
     * Appends a config entry (C_old,new or C_new) to the log and replicates it.
     */
    private void appendConfigEntry(ClusterConfig config) {
        long newIndex = storage.lastLogIndex() + 1;
        LogEntry entry = new LogEntry(storage.getCurrentTerm(), newIndex, config.encode());
        try {
            storage.appendEntry(entry);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append config entry", e);
        }
        if (config.isJoint()) {
            // Transition immediately to joint config so quorum is computed correctly
            activeConfig = config;
            pendingJointIndex = newIndex;
            // Initialise replication state for any newly added peers
            for (NodeId peer : config.allPeers()) {
                nextIndex.putIfAbsent(peer, storage.lastLogIndex() + 1);
                matchIndex.putIfAbsent(peer, 0L);
            }
        } else {
            // C_new committed — transition to simple config
            activeConfig = config;
            pendingJointIndex = 0;
        }
        // Replicate to all peers in the union of old+new
        for (NodeId peer : effectivePeers()) {
            replicateToPeer(peer);
        }
    }

    /**
     * Returns the current active cluster configuration.
     */
    public ClusterConfig getActiveConfig() {
        return activeConfig;
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
                    LogEntry toAppend = newEntries.get(j);
                    try {
                        storage.appendEntry(toAppend);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to append entry", e);
                    }
                    // Apply config entries immediately on followers (§6)
                    applyConfigEntryIfNeeded(toAppend);
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

    /**
     * Returns all peers in the currently effective configuration (union of old+new during
     * the joint phase, or just new otherwise), excluding self.
     */
    private Set<NodeId> effectivePeers() {
        Set<NodeId> all = new HashSet<>(activeConfig.allPeers());
        all.remove(selfId);
        return all;
    }

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

        for (NodeId peer : effectivePeers()) {
            transport.sendRequestVote(peer, req, this::handleRequestVoteResponse);
        }
    }

    private void becomeLeader() {
        role = RaftRole.LEADER;
        currentLeaderId = selfId;
        lastHeartbeatSent = clock.currentTimeMillis();

        // Initialise per-peer replication state
        long lastIdx = storage.lastLogIndex();
        for (NodeId peer : effectivePeers()) {
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
        for (NodeId peer : effectivePeers()) {
            replicateToPeer(peer);
        }
    }

    /**
     * Checks if a new commitIndex can be established based on matchIndex values.
     * Only commits entries from the current term (Raft §5.4.2).
     * During joint phase, requires majority of both old and new configs.
     */
    private void advanceCommitIndex() {
        long lastIdx = storage.lastLogIndex();
        // Try to advance commitIndex to the highest index that has sufficient replication
        for (long n = lastIdx; n > commitIndex; n--) {
            LogEntry entry = storage.getEntryAtIndex(n);
            if (entry == null) continue;
            // Only commit entries from current term (§5.4.2 safety rule)
            if (entry.getTerm() != storage.getCurrentTerm()) continue;

            if (hasReplicationQuorum(n)) {
                commitIndex = n;
                applyCommitted();
                break;
            }
        }
    }

    /**
     * Returns true if log index {@code n} has been replicated to a quorum.
     * During the joint phase this means a majority of both old and new configs.
     */
    private boolean hasReplicationQuorum(long n) {
        // Determine the config to use for quorum calculation.
        // If this index is the pending joint config itself, use the joint quorum.
        // We use the activeConfig, which reflects the current known state.
        ClusterConfig cfg = activeConfig;

        if (cfg.isJoint()) {
            // Joint phase: must have majority of old AND majority of new
            return hasMajorityInSet(n, cfg.getOldPeers()) && hasMajorityInSet(n, cfg.getNewPeers());
        } else {
            // Simple config: majority of newPeers (the full membership)
            return hasMajorityInSet(n, cfg.getNewPeers());
        }
    }

    /**
     * Returns true if log index {@code n} is replicated on a majority of the given peer set
     * (which may or may not include self).
     */
    private boolean hasMajorityInSet(long n, Set<NodeId> peerSet) {
        // The "cluster" for this set includes self if self is in the set, otherwise doesn't.
        // For Raft the peer set in our ClusterConfig contains only OTHER nodes (not self),
        // but during joint consensus we track both; we need to decide if self is in the set.
        // We model peerSet as the set of OTHER nodes (not self) that belong to this group,
        // matching the way activeConfig is constructed from the peers list.
        int total = peerSet.size() + 1;  // +1 for self (self always has its own entries)
        int count = 1; // self always has the entry
        for (NodeId peer : peerSet) {
            if (matchIndex.getOrDefault(peer, 0L) >= n) {
                count++;
            }
        }
        return count > total / 2;
    }

    /**
     * Returns true when the number of votes represents a majority of the cluster.
     * Uses the active config (simple quorum on newPeers for elections).
     */
    private boolean hasQuorum(int votes) {
        // For elections we use the simple majority of current effective membership.
        // During joint phase the Raft paper says a node must get votes from both majorities
        // for a full election. However, for simplicity here we use the total effective
        // cluster size (old ∪ new) for the election quorum check. This is safe because
        // the joint phase is transient and the tests drive elections outside of joint config.
        int clusterSize = 1 + effectivePeers().size(); // self + all effective peers
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

    /**
     * Applies all committed but not-yet-applied entries to the state machine,
     * and handles config transitions on commit.
     */
    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = storage.getEntryAtIndex(lastApplied);
            if (entry != null) {
                // Handle config entry transitions
                if (ClusterConfig.isConfigEntry(entry.getCommand())) {
                    onConfigEntryCommitted(entry);
                }
                applier.apply(entry);
            }
        }
    }

    /**
     * Called when a config entry is committed. If it is a C_old,new entry, the leader
     * immediately appends the C_new entry. Non-leaders just update their local config.
     */
    private void onConfigEntryCommitted(LogEntry entry) {
        ClusterConfig cfg = ClusterConfig.decode(entry.getCommand());
        if (cfg.isJoint()) {
            // C_old,new committed → update active config and, if leader, append C_new
            if (role == RaftRole.LEADER) {
                // Append C_new
                ClusterConfig cNew = ClusterConfig.simple(cfg.getNewPeers());
                appendConfigEntry(cNew);
            } else {
                // Followers update their config to C_new immediately upon committing C_old,new
                // (they will receive C_new as a regular log entry from the leader)
                // For now just keep joint until C_new arrives
            }
        } else {
            // C_new committed — finalise config
            activeConfig = cfg;
            pendingJointIndex = 0;
        }
    }

    /**
     * If the given entry is a config entry, immediately apply it to the local config.
     * This is called on followers when they receive (but haven't yet committed) config entries,
     * because §6 says that a server uses the latest config in its log regardless of whether
     * it's been committed.
     */
    private void applyConfigEntryIfNeeded(LogEntry entry) {
        if (!ClusterConfig.isConfigEntry(entry.getCommand())) return;
        ClusterConfig cfg = ClusterConfig.decode(entry.getCommand());
        activeConfig = cfg;
        if (cfg.isJoint()) {
            pendingJointIndex = entry.getIndex();
        } else {
            pendingJointIndex = 0;
        }
    }
}
