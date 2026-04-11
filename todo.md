## Pending
- [ ] Deployment — Dockerfile (shadowJar from server module), k8s manifests (Namespace, StatefulSet, Service, ConfigMap), healthcheck.sh verifying leader election and read-after-write from non-leader

## In Progress
- [ ] Test harness — implement ClusterHarness (AutoCloseable, node lifecycle, partition/heal via per-link interceptors), awaitLeader/awaitCommitIndex convergence helpers, Spock integration tests for election and replication

## Done
- [x] Gradle build scaffold
- [x] Raft leader election
- [x] Raft log replication
- [x] Raft membership changes
- [x] Store module
- [x] Server module
