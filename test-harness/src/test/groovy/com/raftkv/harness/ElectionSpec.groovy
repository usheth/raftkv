package com.raftkv.harness

import com.raftkv.core.NodeId
import com.raftkv.core.RaftRole
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Duration

/**
 * Spock integration tests for Raft leader election.
 *
 * All tests use a real 3-node cluster running in-process over gRPC on ephemeral ports.
 * No Thread.sleep() in test assertions — convergence helpers poll with a timeout.
 */
@Timeout(30)
class ElectionSpec extends Specification {

    ClusterHarness harness

    def cleanup() {
        harness?.close()
    }

    def "a 3-node cluster elects exactly one leader"() {
        given: "a 3-node cluster is started"
        harness = new ClusterHarness(3)

        when: "we wait for the cluster to elect a stable leader"
        // awaitLeader blocks until exactly one leader AND all others are FOLLOWER
        NodeId leader = harness.awaitLeader(Duration.ofSeconds(10))

        then: "exactly one node is the leader"
        leader != null
        harness.raftNode(leader).getRole() == RaftRole.LEADER

        and: "the other two nodes are followers"
        harness.allNodeIds()
               .findAll { it != leader }
               .every { harness.raftNode(it).getRole() == RaftRole.FOLLOWER }
    }

    def "a network partition causes a new leader election among the majority"() {
        given: "a 3-node cluster with a leader"
        harness = new ClusterHarness(3)
        NodeId originalLeader = harness.awaitLeader(Duration.ofSeconds(10))
        List<NodeId> followers = harness.allNodeIds().findAll { it != originalLeader }
        NodeId follower1 = followers[0]
        NodeId follower2 = followers[1]

        when: "the leader is isolated from both followers"
        harness.partition(originalLeader, follower1)
        harness.partition(originalLeader, follower2)

        then: "a new leader is elected among the two followers (who form a majority)"
        NodeId newLeader = awaitNewLeader(originalLeader, Duration.ofSeconds(10))
        newLeader != null
        newLeader != originalLeader
        harness.raftNode(newLeader).getRole() == RaftRole.LEADER

        and: "the new leader is one of the former followers"
        newLeader == follower1 || newLeader == follower2
    }

    def "after healing the partition the cluster converges to one leader"() {
        given: "a cluster where the leader was isolated and a new leader was elected"
        harness = new ClusterHarness(3)
        NodeId originalLeader = harness.awaitLeader(Duration.ofSeconds(10))
        List<NodeId> followers = harness.allNodeIds().findAll { it != originalLeader }
        NodeId follower1 = followers[0]
        NodeId follower2 = followers[1]

        harness.partition(originalLeader, follower1)
        harness.partition(originalLeader, follower2)
        NodeId newLeader = awaitNewLeader(originalLeader, Duration.ofSeconds(10))
        assert newLeader != null : "a new leader should have been elected before healing"

        when: "the partition is healed"
        harness.heal(originalLeader, follower1)
        harness.heal(originalLeader, follower2)

        then: "the cluster converges to exactly one leader"
        // After healing, the old leader will receive a heartbeat from the new leader
        // with a higher term and step down to follower
        awaitSingleLeader(Duration.ofSeconds(10))
        harness.allNodeIds().count { harness.raftNode(it).getRole() == RaftRole.LEADER } == 1
    }

    // -------------------------------------------------------------------------
    // Polling helpers specific to this spec
    // -------------------------------------------------------------------------

    /**
     * Waits until all non-leader nodes are FOLLOWER (cluster fully stabilised).
     */
    private boolean awaitStableCluster(NodeId leader, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            boolean stable = harness.allNodeIds()
                    .findAll { it != leader }
                    .every { harness.raftNode(it).getRole() == RaftRole.FOLLOWER }
            if (stable) return true
            Thread.sleep(20)
        }
        return false
    }

    /**
     * Waits until a new leader is elected that is NOT {@code excludedNode}.
     */
    private NodeId awaitNewLeader(NodeId excludedNode, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            def leaders = harness.allNodeIds()
                    .findAll { it != excludedNode && harness.raftNode(it).getRole() == RaftRole.LEADER }
            if (leaders.size() == 1) return leaders[0]
            Thread.sleep(20)
        }
        return null
    }

    /**
     * Waits until there is exactly one leader in the cluster.
     */
    private boolean awaitSingleLeader(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            def leaderCount = harness.allNodeIds().count { harness.raftNode(it).getRole() == RaftRole.LEADER }
            if (leaderCount == 1) return true
            Thread.sleep(20)
        }
        return false
    }
}
