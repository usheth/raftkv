## Pending
- [ ] Raft leader election — implement RaftNode skeleton with persistent state (currentTerm, votedFor, append-only log file), RequestVote handler, election timeout model; single-threaded event loop per node
- [ ] Raft log replication — implement AppendEntries handler, leader replication loop (nextIndex/matchIndex per peer), commit index advancement, state machine apply callback
- [ ] Raft membership changes — implement joint consensus config change (§6 Raft paper): AddPeer/RemovePeer, joint config log entry, transition to new config
- [ ] Store module — implement in-memory KVStore, HashRing (MurmurHash3, 256 vnodes), ShardRouter (NOT_LEADER redirect, RESHARDING fencing); shard 0 as config shard for ring mutations
- [ ] Server module — implement RaftServer (gRPC server, ephemeral port in tests), RaftTransportImpl (injectable ClientInterceptors for fault injection), KvServiceImpl; wire all modules together
- [ ] Test harness — implement ClusterHarness (AutoCloseable, node lifecycle, partition/heal via per-link interceptors), awaitLeader/awaitCommitIndex convergence helpers, Spock integration tests for election and replication
- [ ] Deployment — Dockerfile (shadowJar from server module), k8s manifests (Namespace, StatefulSet, Service, ConfigMap), healthcheck.sh verifying leader election and read-after-write from non-leader

## In Progress
- [ ] Gradle build scaffold — set up settings.gradle and per-module build.gradle files with fully pinned dependency versions; ./gradlew build passes on empty source trees; proto module generates Java from proto files

## Done
