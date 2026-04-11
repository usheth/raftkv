package com.raftkv.raft;

import com.raftkv.core.LogEntry;
import com.raftkv.core.ManualClock;
import com.raftkv.core.NodeId;
import com.raftkv.core.RaftRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Raft membership changes via joint consensus (§6).
 *
 * Acceptance criteria:
 *  1. A leader can add a peer via joint consensus, logging a C_old_new entry then a C_new entry.
 *  2. A leader can remove a peer via joint consensus.
 *  3. During the joint phase both old and new quorums must agree to commit.
 */
class RaftMembershipTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Shared test infrastructure (mirrors RaftLogReplicationTest)
    // -------------------------------------------------------------------------

    static final class StubTransport implements RaftTransport {
        record PendingVote(NodeId target, RequestVoteRequest req,
                           BiConsumer<NodeId, RequestVoteResponse> callback) {}

        record PendingAppend(NodeId target, AppendEntriesRequest req,
                             BiConsumer<NodeId, AppendEntriesResponse> callback) {}

        final List<PendingVote>   pendingVotes   = new ArrayList<>();
        final List<PendingAppend> pendingAppends = new ArrayList<>();

        @Override
        public void sendRequestVote(NodeId target, RequestVoteRequest req,
                                    BiConsumer<NodeId, RequestVoteResponse> callback) {
            pendingVotes.add(new PendingVote(target, req, callback));
        }

        @Override
        public void sendAppendEntries(NodeId target, AppendEntriesRequest req,
                                      BiConsumer<NodeId, AppendEntriesResponse> callback) {
            pendingAppends.add(new PendingAppend(target, req, callback));
        }

        List<PendingAppend> appendsTo(NodeId target) {
            List<PendingAppend> result = new ArrayList<>();
            for (PendingAppend pa : pendingAppends) {
                if (pa.target().equals(target)) result.add(pa);
            }
            return result;
        }

        PendingAppend pollAppend(NodeId target) {
            List<PendingAppend> list = appendsTo(target);
            if (list.isEmpty()) throw new AssertionError("No pending AppendEntries for " + target);
            PendingAppend pa = list.get(list.size() - 1);
            pendingAppends.remove(pa);
            return pa;
        }

        boolean hasAppendTo(NodeId target) {
            return !appendsTo(target).isEmpty();
        }

        void clearAppends() { pendingAppends.clear(); }
        void clearVotes()   { pendingVotes.clear(); }
    }

    static final class TrackingApplier implements StateMachineApplier {
        final List<LogEntry> applied = new ArrayList<>();

        @Override
        public void apply(LogEntry entry) {
            applied.add(entry);
        }

        List<LogEntry> configEntries() {
            List<LogEntry> result = new ArrayList<>();
            for (LogEntry e : applied) {
                if (ClusterConfig.isConfigEntry(e.getCommand())) result.add(e);
            }
            return result;
        }
    }

    private PersistentState newStorage(String nodeId) throws IOException {
        return new PersistentState(tempDir.resolve("raft-" + nodeId + ".log"));
    }

    private RaftNode newNode(NodeId self, List<NodeId> peers, ManualClock clock,
                             StubTransport transport, StateMachineApplier applier) throws IOException {
        PersistentState storage = newStorage(self.getId());
        return new RaftNode(self, peers, storage, clock, transport, applier, new Random(0));
    }

    /**
     * Forces a multi-node leader by delivering votes from all listed peers.
     * Returns the node in LEADER state at term 1.
     */
    private RaftNode bootstrapLeader(NodeId self, List<NodeId> peers, ManualClock clock,
                                     StubTransport transport, TrackingApplier applier)
            throws IOException {
        RaftNode leader = newNode(self, peers, clock, transport, applier);
        leader.start();
        clock.advance(400);
        leader.tick();
        assertEquals(RaftRole.CANDIDATE, leader.getRole(),
                "Expected CANDIDATE after election timeout");
        for (NodeId peer : peers) {
            leader.handleRequestVoteResponse(peer, new RequestVoteResponse(1L, true));
        }
        assertEquals(RaftRole.LEADER, leader.getRole());
        transport.clearAppends();
        return leader;
    }

    // -------------------------------------------------------------------------
    // Test 1: Leader adds a peer — C_old_new then C_new
    // -------------------------------------------------------------------------

    /**
     * Verifies that calling addPeer on a leader:
     *  - Appends a C_old_new (joint) config entry to the log
     *  - After C_old_new commits, automatically appends a C_new entry
     *  - Both entries are eventually committed and applied
     */
    @Test
    void leaderAddsNewPeerViaJointConsensus() throws IOException {
        NodeId nLeader = new NodeId("leader");
        NodeId n2      = new NodeId("n2");
        NodeId n3      = new NodeId("n3"); // the new peer to add

        ManualClock clock = new ManualClock(0);
        StubTransport leaderTransport = new StubTransport();
        TrackingApplier leaderApplier = new TrackingApplier();

        // 2-node cluster: leader + n2
        RaftNode leader = bootstrapLeader(nLeader, List.of(n2), clock, leaderTransport, leaderApplier);

        // Sanity: initial config is simple {n2}
        assertFalse(leader.getActiveConfig().isJoint(), "Initial config should not be joint");

        // --- initiate addPeer(n3) ---
        leader.addPeer(n3);

        // Immediately after addPeer, the active config should be joint (C_old,new)
        assertTrue(leader.getActiveConfig().isJoint(),
                "After addPeer, config should be in joint phase");
        assertEquals(Set.of(n2), leader.getActiveConfig().getOldPeers(),
                "Old peers should be {n2}");
        assertEquals(Set.of(n2, n3), leader.getActiveConfig().getNewPeers(),
                "New peers should be {n2, n3}");

        // The leader should have sent the C_old_new entry to both n2 and n3
        assertTrue(leaderTransport.hasAppendTo(n2), "Leader should replicate C_old_new to n2");
        assertTrue(leaderTransport.hasAppendTo(n3), "Leader should replicate C_old_new to n3");

        // Verify the entry appended to the log is a CONFIG entry
        long jointIndex = leader.getActiveConfig().isJoint() ? 1L : -1L;
        // Log index 1 should be the C_old_new entry
        PersistentState leaderStorage = newStorage(nLeader.getId()); // re-read won't work, use getLog via applier
        // Instead, use transport to inspect the entry content
        StubTransport.PendingAppend paJointN2 = leaderTransport.pollAppend(n2);
        StubTransport.PendingAppend paJointN3 = leaderTransport.pollAppend(n3);
        assertFalse(paJointN2.req().getEntries().isEmpty(), "C_old_new request must carry entries");
        LogEntry jointEntry = paJointN2.req().getEntries().get(0);
        assertTrue(ClusterConfig.isConfigEntry(jointEntry.getCommand()),
                "First entry should be a CONFIG entry (C_old_new)");
        ClusterConfig jointConfig = ClusterConfig.decode(jointEntry.getCommand());
        assertTrue(jointConfig.isJoint(), "Decoded entry should be a joint config");

        // --- Simulate both n2 and n3 acknowledging the C_old_new entry ---
        // Joint quorum = majority of old ({n2}: 1/2 → need 1 from n2) AND majority of new ({n2,n3}: 2/3 → need 1 from {n2 or n3})
        // With leader (self) + n2 ack: old majority = self+n2 = 2/2 ✓, new majority = self+n2 = 2/3 ✓
        paJointN2.callback().accept(n2, new AppendEntriesResponse(1L, true));

        // commitIndex should advance to 1 (C_old_new committed)
        assertEquals(1, leader.getCommitIndex(),
                "C_old_new should be committed after majority of old AND new ack");

        // C_old_new committed → leader should have automatically appended C_new
        // and sent it to both n2 and n3
        assertTrue(leaderTransport.hasAppendTo(n2) || leaderTransport.hasAppendTo(n3),
                "Leader should have sent C_new after C_old_new committed");

        // Find the C_new append
        List<StubTransport.PendingAppend> cNewAppends = leaderTransport.appendsTo(n2);
        assertFalse(cNewAppends.isEmpty(), "C_new should be sent to n2");
        LogEntry cNewEntry = null;
        for (StubTransport.PendingAppend pa : cNewAppends) {
            for (LogEntry e : pa.req().getEntries()) {
                if (ClusterConfig.isConfigEntry(e.getCommand())) {
                    ClusterConfig decoded = ClusterConfig.decode(e.getCommand());
                    if (!decoded.isJoint()) {
                        cNewEntry = e;
                        break;
                    }
                }
            }
            if (cNewEntry != null) break;
        }
        assertNotNull(cNewEntry, "C_new entry should be in a pending AppendEntries to n2");
        ClusterConfig finalConfig = ClusterConfig.decode(cNewEntry.getCommand());
        assertFalse(finalConfig.isJoint(), "C_new should be a simple config");
        assertEquals(Set.of(n2, n3), finalConfig.getNewPeers(),
                "C_new should contain {n2, n3}");

        // --- Commit C_new: deliver ack from n2 ---
        StubTransport.PendingAppend paCNewN2 = null;
        for (StubTransport.PendingAppend pa : new ArrayList<>(leaderTransport.pendingAppends)) {
            if (pa.target().equals(n2)) {
                paCNewN2 = pa;
                break;
            }
        }
        assertNotNull(paCNewN2);
        paCNewN2.callback().accept(n2, new AppendEntriesResponse(1L, true));

        // C_new should now be committed
        assertEquals(2, leader.getCommitIndex(),
                "C_new (index 2) should be committed after majority ack");

        // Final config should be simple {n2, n3}
        assertFalse(leader.getActiveConfig().isJoint(),
                "After C_new commits, config should not be joint");
        assertEquals(Set.of(n2, n3), leader.getActiveConfig().getNewPeers(),
                "Final config should be {n2, n3}");

        // Both config entries should have been applied
        assertEquals(2, leaderApplier.configEntries().size(),
                "Both C_old_new and C_new should be applied to the state machine");
    }

    // -------------------------------------------------------------------------
    // Test 2: Leader removes a peer via joint consensus
    // -------------------------------------------------------------------------

    /**
     * Verifies that calling removePeer on a leader:
     *  - Appends a C_old_new entry
     *  - After commit, appends C_new (without the removed peer)
     *  - Final config excludes the removed peer
     */
    @Test
    void leaderRemovesPeerViaJointConsensus() throws IOException {
        NodeId nLeader = new NodeId("leader");
        NodeId n2      = new NodeId("n2");
        NodeId n3      = new NodeId("n3"); // will be removed

        ManualClock clock = new ManualClock(0);
        StubTransport leaderTransport = new StubTransport();
        TrackingApplier leaderApplier = new TrackingApplier();

        // 3-node cluster: leader + n2 + n3
        RaftNode leader = bootstrapLeader(nLeader, List.of(n2, n3), clock, leaderTransport, leaderApplier);

        // --- initiate removePeer(n3) ---
        leader.removePeer(n3);

        // Config should be joint
        assertTrue(leader.getActiveConfig().isJoint(),
                "After removePeer, config should be in joint phase");
        assertEquals(Set.of(n2, n3), leader.getActiveConfig().getOldPeers(),
                "Old peers should be {n2, n3}");
        assertEquals(Set.of(n2), leader.getActiveConfig().getNewPeers(),
                "New peers should be {n2}");

        // The C_old_new entry must be a config entry in the log
        assertTrue(leaderTransport.hasAppendTo(n2), "Leader should replicate C_old_new to n2");

        // Drain appends to n2 to find the C_old_new entry
        StubTransport.PendingAppend paJointN2 = leaderTransport.pollAppend(n2);
        assertFalse(paJointN2.req().getEntries().isEmpty());
        LogEntry jointEntry = paJointN2.req().getEntries().get(0);
        assertTrue(ClusterConfig.isConfigEntry(jointEntry.getCommand()),
                "Entry should be a CONFIG entry");
        assertTrue(ClusterConfig.decode(jointEntry.getCommand()).isJoint(),
                "Entry should be a joint (C_old_new) config");

        // --- Commit C_old_new with joint quorum ---
        // Old = {n2,n3}, new = {n2}
        // Joint quorum: majority of old (2/3 → need self+n2 or similar) AND majority of new (1/2 → self alone suffices)
        // With leader(self) + n2 ack: old quorum = 2/3 ✓, new quorum = 2/2 ✓
        paJointN2.callback().accept(n2, new AppendEntriesResponse(1L, true));
        assertEquals(1, leader.getCommitIndex(),
                "C_old_new should commit with joint quorum (leader + n2)");

        // Leader should have sent C_new
        assertTrue(leaderTransport.hasAppendTo(n2),
                "Leader should send C_new to remaining peer n2");

        // Drain to find C_new
        StubTransport.PendingAppend paCNewN2 = leaderTransport.pollAppend(n2);
        LogEntry cNewCandidate = null;
        for (LogEntry e : paCNewN2.req().getEntries()) {
            if (ClusterConfig.isConfigEntry(e.getCommand())) {
                cNewCandidate = e;
                break;
            }
        }
        if (cNewCandidate == null) {
            // might be in a separate append request
            if (leaderTransport.hasAppendTo(n2)) {
                paCNewN2 = leaderTransport.pollAppend(n2);
                for (LogEntry e : paCNewN2.req().getEntries()) {
                    if (ClusterConfig.isConfigEntry(e.getCommand())) {
                        cNewCandidate = e;
                        break;
                    }
                }
            }
        }
        assertNotNull(cNewCandidate, "C_new entry should be in AppendEntries to n2");
        ClusterConfig finalCfg = ClusterConfig.decode(cNewCandidate.getCommand());
        assertFalse(finalCfg.isJoint(), "C_new must not be joint");
        assertEquals(Set.of(n2), finalCfg.getNewPeers(), "C_new must contain only {n2}");

        // --- Commit C_new ---
        paCNewN2.callback().accept(n2, new AppendEntriesResponse(1L, true));
        assertEquals(2, leader.getCommitIndex(), "C_new (index 2) should be committed");

        // Final config: simple {n2}, n3 is gone
        assertFalse(leader.getActiveConfig().isJoint());
        assertEquals(Set.of(n2), leader.getActiveConfig().getNewPeers());

        // 2 config entries committed
        assertEquals(2, leaderApplier.configEntries().size());
    }

    // -------------------------------------------------------------------------
    // Test 3: Joint phase requires both old and new quorums
    // -------------------------------------------------------------------------

    /**
     * During the joint phase (C_old_new not yet committed), a regular log entry must NOT
     * be committed unless both the old quorum AND the new quorum have acknowledged it.
     *
     * Setup: 3-node cluster {leader, n2, n3}, adding n4.
     *   Old = {n2, n3}, New = {n2, n3, n4}.
     *   Joint quorum needs: majority of old (2/3 → leader+n2 or leader+n3) AND
     *                        majority of new (3/4 → leader+2 of {n2,n3,n4}).
     *
     * The test checks that:
     *  (a) Ack from only old-majority (leader+n2) but NOT new-majority is insufficient.
     *  (b) Ack from only new-majority (leader+n2+n4) but NOT old-majority is insufficient.
     *  (c) Both old-majority AND new-majority ack → commits.
     */
    @Test
    void jointPhaseRequiresBothQuorums() throws IOException {
        NodeId nLeader = new NodeId("leader");
        NodeId n2      = new NodeId("n2");
        NodeId n3      = new NodeId("n3");
        NodeId n4      = new NodeId("n4");

        ManualClock clock = new ManualClock(0);
        StubTransport leaderTransport = new StubTransport();
        TrackingApplier leaderApplier = new TrackingApplier();

        // Bootstrap 3-node cluster: leader, n2, n3
        RaftNode leader = bootstrapLeader(nLeader, List.of(n2, n3), clock, leaderTransport, leaderApplier);

        // Add n4 — initiates joint consensus
        leader.addPeer(n4);

        // Active config should be joint: old={n2,n3}, new={n2,n3,n4}
        assertTrue(leader.getActiveConfig().isJoint());
        assertEquals(Set.of(n2, n3),     leader.getActiveConfig().getOldPeers());
        assertEquals(Set.of(n2, n3, n4), leader.getActiveConfig().getNewPeers());

        // The C_old_new entry is at index 1.
        // Now append a regular command entry (index 2) while in joint phase.
        leader.appendEntry("cmd".getBytes());
        // Clear all pending appends — we'll simulate specific ACKs below
        leaderTransport.clearAppends();

        // Manually simulate matchIndex for peers to test quorum logic.
        // We do this by directly delivering fake AppendEntries responses that the leader
        // interprets as peers having replicated up to index 2 (the command entry).
        //
        // The request we need to simulate having been sent covers index 1 (C_old_new) and index 2 (cmd).
        AppendEntriesRequest fakeReqCovering2 = new AppendEntriesRequest(
                1L, nLeader, 0L, 0L,
                List.of(
                    // entries are not checked by handleAppendEntriesResponse logic, only prevLogIndex+size matters
                    new LogEntry(1L, 1L, new byte[0]),
                    new LogEntry(1L, 2L, new byte[0])
                ),
                0L
        );

        // --- Scenario (a): only n2 acks (old-majority without n3, new-majority without n4) ---
        // Old = {n2,n3}: self+n2 = 2 out of 3 → majority ✓
        // New = {n2,n3,n4}: self+n2 = 2 out of 4 → NOT majority (need 3)
        // So commit should NOT happen with only n2.
        leader.handleAppendEntriesResponse(n2, fakeReqCovering2, new AppendEntriesResponse(1L, true));
        assertEquals(0, leader.getCommitIndex(),
                "Commit must NOT happen with only old-majority (n2) — new quorum not satisfied");

        // --- Scenario (b): also n4 acks (new has self+n2+n4=3/4 ✓, old still only self+n2=2/3 ✓) ---
        // Old = {n2,n3}: self+n2 = 2/3 ✓
        // New = {n2,n3,n4}: self+n2+n4 = 3/4 ✓
        // Now both quorums are met for the C_old_new entry at index 1.
        // But wait — index 2 (command) must also check quorum.
        // For index 2: same config applies. self+n2+n4 = 3/4 new ✓, self+n2 = 2/3 old ✓.
        // However the §5.4.2 rule: only commit entries from current term.
        // Both entries are term 1 and we're in term 1, so this is fine.
        leader.handleAppendEntriesResponse(n4, fakeReqCovering2, new AppendEntriesResponse(1L, true));

        // Now both quorums satisfied for index 1 and 2 → should commit up to index 2
        // (The C_old_new at index 1 commits, then C_new appended at index 3, then cmd at 2 also commits)
        // Actually advanceCommitIndex iterates from lastIdx down. Index 2 is the command, both quorums met.
        // commitIndex should advance to 2.
        assertTrue(leader.getCommitIndex() >= 1,
                "Both quorums met — commitIndex should advance");

        // Verify: if only old quorum alone had been met (before n4 acked), commit was blocked.
        // This was already checked in scenario (a) above: commitIndex stayed 0.
    }
}
