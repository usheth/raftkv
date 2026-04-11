package com.raftkv.harness

import com.raftkv.core.NodeId
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Duration

/**
 * Spock integration tests for Raft log replication.
 *
 * All tests use a real 3-node cluster running in-process over gRPC on ephemeral ports.
 */
@Timeout(30)
class ReplicationSpec extends Specification {

    ClusterHarness harness

    def cleanup() {
        harness?.close()
    }

    def "a leader replicates a log entry to all followers and all nodes commit it"() {
        given: "a 3-node cluster with an elected leader"
        harness = new ClusterHarness(3)
        NodeId leader = harness.awaitLeader(Duration.ofSeconds(10))

        when: "the leader receives a client command"
        harness.propose(leader, "set x=1".bytes)

        then: "all nodes commit the entry (commitIndex >= 1)"
        harness.awaitCommitIndex(1, Duration.ofSeconds(10))

        and: "the leader has replicated the entry"
        harness.raftNode(leader).getCommitIndex() >= 1

        and: "each follower has committed the entry"
        harness.allNodeIds()
               .findAll { it != leader }
               .every { harness.raftNode(it).getCommitIndex() >= 1 }
    }

    def "multiple log entries are replicated and committed in order"() {
        given: "a 3-node cluster with an elected leader"
        harness = new ClusterHarness(3)
        NodeId leader = harness.awaitLeader(Duration.ofSeconds(10))

        when: "three commands are proposed"
        harness.propose(leader, "set a=1".bytes)
        harness.propose(leader, "set b=2".bytes)
        harness.propose(leader, "set c=3".bytes)

        then: "all nodes eventually commit all three entries"
        harness.awaitCommitIndex(3, Duration.ofSeconds(10))

        and: "every node has a commit index of at least 3"
        harness.allNodeIds().every { harness.raftNode(it).getCommitIndex() >= 3 }
    }
}
