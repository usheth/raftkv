package com.raftkv.raft;

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
 * Unit tests for Raft leader election.
 *
 * <p>All timing is controlled via {@link ManualClock}. No {@link Thread#sleep(long)} is used.
 * A stub {@link RaftTransport} captures outbound RPCs and lets the test deliver responses
 * manually or synchronously.
 */
class RaftLeaderElectionTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Stub transport that captures sent RPCs
    // -------------------------------------------------------------------------

    /**
     * Stub transport that records RequestVote calls so tests can inspect them and deliver
     * responses manually.
     */
    static final class StubTransport implements RaftTransport {

        record PendingVote(NodeId target, RequestVoteRequest req,
                           BiConsumer<NodeId, RequestVoteResponse> callback) {}

        final List<PendingVote> pendingVotes = new ArrayList<>();

        @Override
        public void sendRequestVote(NodeId target, RequestVoteRequest req,
                                    BiConsumer<NodeId, RequestVoteResponse> callback) {
            pendingVotes.add(new PendingVote(target, req, callback));
        }

        @Override
        public void sendAppendEntries(NodeId target, AppendEntriesRequest req,
                                      BiConsumer<NodeId, AppendEntriesResponse> callback) {
            // Not needed for election tests — no-op.
        }

        /** Deliver a response to the first pending vote matching {@code target}. */
        void deliverVote(NodeId target, RequestVoteResponse resp) {
            Iterator<PendingVote> it = pendingVotes.iterator();
            while (it.hasNext()) {
                PendingVote pv = it.next();
                if (pv.target().equals(target)) {
                    it.remove();
                    pv.callback().accept(target, resp);
                    return;
                }
            }
            throw new AssertionError("No pending vote for " + target);
        }

        /** Clears all pending votes. */
        void clear() {
            pendingVotes.clear();
        }
    }

    // No-op applier for tests that don't need state machine application.
    private static final StateMachineApplier NO_OP_APPLIER = entry -> {};

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PersistentState newStorage(String nodeId) throws IOException {
        return new PersistentState(tempDir.resolve("raft-" + nodeId + ".log"));
    }

    /**
     * Creates a RaftNode with a fixed Random(0) to make timeouts reproducible.
     * With seed 0 the first timeout drawn will be deterministic.
     */
    private RaftNode newNode(NodeId self, List<NodeId> peers, ManualClock clock,
                             StubTransport transport) throws IOException {
        PersistentState storage = newStorage(self.getId());
        return new RaftNode(self, peers, storage, clock, transport, NO_OP_APPLIER, new Random(0));
    }

    // -------------------------------------------------------------------------
    // Test 1: Single node elects itself
    // -------------------------------------------------------------------------

    /**
     * A single-node cluster should immediately become LEADER once its election timeout
     * elapses, because it is the only voter.
     */
    @Test
    void singleNodeElectsItself() throws IOException {
        NodeId n1 = new NodeId("n1");
        ManualClock clock = new ManualClock(0);
        StubTransport transport = new StubTransport();

        RaftNode node = newNode(n1, List.of(), clock, transport);
        node.start();

        assertEquals(RaftRole.FOLLOWER, node.getRole(), "should start as follower");

        // Advance clock well past the maximum election timeout (300 ms).
        clock.advance(400);
        node.tick();

        assertEquals(RaftRole.LEADER, node.getRole(), "single node should elect itself");
        assertEquals(n1, node.getLeaderId(), "leader should know itself");
        assertEquals(1, node.getCurrentTerm(), "term should be 1 after first election");
        assertTrue(transport.pendingVotes.isEmpty(), "no RPCs to send in a 1-node cluster");
    }

    // -------------------------------------------------------------------------
    // Test 2: Three-node election — one node wins
    // -------------------------------------------------------------------------

    /**
     * In a 3-node cluster, one node starts an election and receives votes from both peers;
     * it should become the leader.
     */
    @Test
    void threeNodeElection() throws IOException {
        NodeId n1 = new NodeId("n1");
        NodeId n2 = new NodeId("n2");
        NodeId n3 = new NodeId("n3");

        ManualClock clock = new ManualClock(0);
        StubTransport transport = new StubTransport();

        // n1 is the candidate; n2/n3 will have their own nodes to process RequestVote.
        RaftNode node1 = newNode(n1, List.of(n2, n3), clock, transport);
        RaftNode node2 = newNode(n2, List.of(n1, n3), clock, new StubTransport());
        RaftNode node3 = newNode(n3, List.of(n1, n2), clock, new StubTransport());
        node1.start();
        node2.start();
        node3.start();

        // Advance time past the election timeout so n1 starts an election.
        clock.advance(400);
        node1.tick();

        assertEquals(RaftRole.CANDIDATE, node1.getRole(), "n1 should be a candidate");
        assertEquals(2, transport.pendingVotes.size(), "n1 should have sent 2 RequestVote RPCs");

        // Let n2 and n3 handle the RequestVote and record their responses.
        RequestVoteRequest rvReqForN2 = transport.pendingVotes.get(0).req();
        RequestVoteRequest rvReqForN3 = transport.pendingVotes.get(1).req();

        RequestVoteResponse respFromN2 = node2.handleRequestVote(rvReqForN2);
        RequestVoteResponse respFromN3 = node3.handleRequestVote(rvReqForN3);

        assertTrue(respFromN2.isVoteGranted(), "n2 should grant vote to n1");
        assertTrue(respFromN3.isVoteGranted(), "n3 should grant vote to n1");

        // Deliver the responses back to n1.
        node1.handleRequestVoteResponse(n2, respFromN2);

        // After one granted vote n1 has: 1 (self) + 1 (n2) = 2 out of 3. That is a quorum.
        assertEquals(RaftRole.LEADER, node1.getRole(), "n1 should become leader after quorum");
        assertEquals(n1, node1.getLeaderId());
    }

    // -------------------------------------------------------------------------
    // Test 3: Split vote — higher-term candidate wins
    // -------------------------------------------------------------------------

    /**
     * Two nodes both time out and start elections simultaneously, but node A is already
     * at a higher term. The higher-term candidate should win and the lower-term candidate
     * should revert to follower.
     */
    @Test
    void splitVoteResolvesWithHigherTerm() throws IOException {
        NodeId nA = new NodeId("nA");
        NodeId nB = new NodeId("nB");
        NodeId nC = new NodeId("nC");

        ManualClock clock = new ManualClock(0);
        StubTransport transportA = new StubTransport();
        StubTransport transportB = new StubTransport();

        RaftNode nodeA = newNode(nA, List.of(nB, nC), clock, transportA);
        RaftNode nodeB = newNode(nB, List.of(nA, nC), clock, transportB);
        RaftNode nodeC = newNode(nC, List.of(nA, nB), clock, new StubTransport());
        nodeA.start();
        nodeB.start();
        nodeC.start();

        // Advance clock and tick both A and B to start elections.
        clock.advance(400);
        nodeA.tick(); // starts election in term 1
        nodeB.tick(); // also starts election in term 1

        assertEquals(RaftRole.CANDIDATE, nodeA.getRole());
        assertEquals(RaftRole.CANDIDATE, nodeB.getRole());
        assertEquals(1L, nodeA.getCurrentTerm());
        assertEquals(1L, nodeB.getCurrentTerm());

        // Simulate A receiving B's RequestVote (with term 1 — same term).
        // A has already voted for itself, so it should deny B.
        RequestVoteRequest bReq = transportB.pendingVotes.stream()
                .filter(pv -> pv.target().equals(nA))
                .findFirst().orElseThrow().req();
        RequestVoteResponse aReplyToB = nodeA.handleRequestVote(bReq);
        assertFalse(aReplyToB.isVoteGranted(), "A already voted for itself, should deny B");

        // Now advance A to a higher term (simulate A timing out again).
        clock.advance(400);
        transportA.clear();
        nodeA.tick(); // A starts election in term 2

        assertEquals(2L, nodeA.getCurrentTerm());
        assertEquals(RaftRole.CANDIDATE, nodeA.getRole());

        // C receives A's term-2 RequestVote.
        RequestVoteRequest aReqForC = transportA.pendingVotes.stream()
                .filter(pv -> pv.target().equals(nC))
                .findFirst().orElseThrow().req();
        RequestVoteResponse cReplyToA = nodeC.handleRequestVote(aReqForC);
        assertTrue(cReplyToA.isVoteGranted(), "C should grant vote to A (term 2)");

        // A receives C's vote — quorum reached (self + C = 2 out of 3).
        nodeA.handleRequestVoteResponse(nC, cReplyToA);
        assertEquals(RaftRole.LEADER, nodeA.getRole(), "A should win with higher term");

        // B receives A's higher-term response and should revert to follower.
        RequestVoteResponse higherTermResp = new RequestVoteResponse(2L, false);
        nodeB.handleRequestVoteResponse(nA, higherTermResp);
        assertEquals(RaftRole.FOLLOWER, nodeB.getRole(), "B should revert to follower on higher term");
        assertEquals(2L, nodeB.getCurrentTerm(), "B's term should be updated");
    }

    // -------------------------------------------------------------------------
    // Test 4: Candidate reverts to follower on higher-term response
    // -------------------------------------------------------------------------

    /**
     * A candidate receiving a response with a higher term than its own must revert to
     * follower and update its term.
     */
    @Test
    void candidateRevertsOnHigherTermResponse() throws IOException {
        NodeId n1 = new NodeId("n1");
        NodeId n2 = new NodeId("n2");

        ManualClock clock = new ManualClock(0);
        StubTransport transport = new StubTransport();

        RaftNode node = newNode(n1, List.of(n2), clock, transport);
        node.start();

        // Trigger an election.
        clock.advance(400);
        node.tick();

        assertEquals(RaftRole.CANDIDATE, node.getRole());
        assertEquals(1L, node.getCurrentTerm());

        // Deliver a response with a higher term (e.g., 5).
        RequestVoteResponse higherTermResp = new RequestVoteResponse(5L, false);
        node.handleRequestVoteResponse(n2, higherTermResp);

        assertEquals(RaftRole.FOLLOWER, node.getRole(), "should revert to follower");
        assertEquals(5L, node.getCurrentTerm(), "term should be updated to 5");
        assertNull(node.getLeaderId(), "leader is unknown after reverting");
    }
}
