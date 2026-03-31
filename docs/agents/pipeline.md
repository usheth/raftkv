# Agent Pipeline

## Overview

The project is built using a three-agent pipeline. A coordinator breaks the project into atomic tasks, a coding agent implements each one, and a deployment agent validates each increment against a local Kubernetes cluster.

```
Coordinator → [task] → Coding Agent → [code] → Deployment Agent → [status] → Coordinator
```

## Coordinator Agent

**Responsibility:** Read `AGENTS.md` and the relevant `docs/agents/` files, then decompose the project into an ordered list of atomic tasks. Hand each task to the coding agent one at a time. Collect deployment results and decide whether to proceed or re-issue a corrected task.

**What makes a task atomic:**
- Produces a runnable artifact (the cluster can be redeployed after it)
- Has a single clear goal (e.g. "implement leader election in the raft module")
- Is independent enough to be reviewed in isolation

**Task handoff format:**
```
Task: <short title>
Goal: <one sentence>
Relevant docs: <list of docs/agents/ files to load>
Acceptance: <what the deployment agent should verify>
```

**State files:** The coordinator maintains two files at the repo root:
- `progress.md` — append-only log of completed tasks, their outcomes, and deployment results
- `todo.md` — the current ordered task list; update it as tasks are added, completed, or revised

Both files must be kept up to date throughout the pipeline run. Update `progress.md` after each deployment result and `todo.md` before handing off each new task.

**On deployment failure:** The coordinator receives the deployment agent's report, determines if it's a code issue or infra issue, and either re-issues the task with the error as context or escalates to the user.

## Coding Agent

**Responsibility:** Receive a single task from the coordinator. Load only the `docs/agents/` files listed in the handoff. Implement the task. Do not implement beyond the task scope.

**Rules:**
- Read existing code before modifying anything
- Do not add features not requested in the task
- When done, report back a summary of what changed and any open questions for the coordinator

## Deployment Agent

**Responsibility:** After each coding task, build the Docker image, deploy to the local k8s cluster, and report status back to the coordinator.

**Steps:**
1. Run `./gradlew build` — fail fast if the build breaks
2. Build Docker image: `docker build -t raftkv:dev .`
3. Load into local cluster: see `docs/agents/deployment.md` for the cluster type
4. Apply manifests: `kubectl apply -f k8s/`
5. Wait for rollout: `kubectl rollout status deployment/raftkv-server`
6. Run smoke check: see `docs/agents/deployment.md` for the smoke check command
7. Report: pass/fail, pod logs on failure, any `kubectl describe` output relevant to the failure

**On failure:** Report the full failure context to the coordinator. Do not attempt to fix code.
