# Deployment

## Key Decisions

**Local Kubernetes via minikube.** Assume minikube is running. Images are loaded directly into minikube (`minikube image load`) with `imagePullPolicy: Never` — no registry needed.

**StatefulSet, not Deployment.** Each pod has a stable identity and persistent storage for the Raft log.

**Fat jar via shadowJar.** The Dockerfile builds a single executable jar from the `server` module.

**Namespace:** `raftkv`

## Smoke Check Requirement

After each rollout the deployment agent must:
1. Verify at least one Raft leader has been elected per shard
2. Write a key to the cluster
3. Read it back from a different node (not the leader) to confirm replication

All three must pass before reporting success. The coding agent is responsible for providing a healthcheck script at `scripts/healthcheck.sh` that performs these checks.
