## Pending
- [ ] Store module — implement in-memory KVStore, HashRing (MurmurHash3, 256 vnodes), ShardRouter (NOT_LEADER redirect, RESHARDING fencing); shard 0 as config shard for ring mutations
- [ ] Server module — implement RaftServer (gRPC server, ephemeral port in tests), RaftTransportImpl (injectable ClientInterceptors for fault injection), KvServiceImpl; wire all modules together
- [ ] Test harness — implement ClusterHarness (AutoCloseable, node lifecycle, partition/heal via per-link interceptors), awaitLeader/awaitCommitIndex convergence helpers, Spock integration tests for election and replication
- [ ] Deployment — Dockerfile (shadowJar from server module), k8s manifests (Namespace, StatefulSet, Service, ConfigMap), healthcheck.sh verifying leader election and read-after-write from non-leader

## In Progress
- [ ] Raft membership changes — implement joint consensus config change (§6 Raft paper): AddPeer/RemovePeer, joint config log entry, transition to new config

## Done
- [x] Gradle build scaffold
- [x] Raft leader election
- [x] Raft log replication
