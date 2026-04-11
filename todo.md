## Pending
- [ ] Smoke test verification — run `docker build -t raftkv:dev .`, `minikube image load raftkv:dev`, `kubectl apply -f k8s/`, wait for rollout, and run `scripts/healthcheck.sh` to verify leader election and read-after-write from non-leader

## In Progress

## Done
- [x] Gradle build scaffold
- [x] Raft leader election
- [x] Raft log replication
- [x] Raft membership changes
- [x] Store module
- [x] Server module
- [x] Test harness
- [x] Deployment — Dockerfile, k8s manifests, healthcheck.sh implemented and reviewed
