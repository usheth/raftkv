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

`progress.md` entry format:
```
## <task title>
- Started: <timestamp>
- Attempt <n>: <PASS|FAIL> — <one sentence reason>
- Completed: <timestamp>
- Time taken: <duration>
```
If a task required retries, every attempt must be logged with its failure reason so patterns are visible.

**Retry budget:** A task may be re-issued at most 6 times. On the 7th failure the coordinator halts the pipeline and escalates to the user.

**Human escalation:** Escalation means writing a `BLOCKED.md` at the repo root describing the task, the failure, and what decision is needed from the user. The pipeline stops until `BLOCKED.md` is deleted.

**On deployment failure:** The coordinator receives the deployment agent's report, determines if it's a code issue or infra issue, and either re-issues the task with the error as context or escalates to the user.

## Coding Agent

**Responsibility:** Receive a single task from the coordinator. Load only the `docs/agents/` files listed in the handoff. Implement the task. Do not implement beyond the task scope.

**Each task runs in a fresh agent session.** The coordinator must not reuse a coding agent across tasks. Context from previous tasks must not carry over.

**Rules:**
- Read existing code before modifying anything
- Do not add features not requested in the task
- Run `./gradlew test` before reporting done — do not hand off if tests fail
- Commit all changes with a meaningful message before reporting done
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

**On failure:** Roll back to the last known good image (`kubectl rollout undo`) to restore the cluster before reporting. Then report the full failure context to the coordinator. Do not attempt to fix code.

## Definition of Done

The pipeline is complete when all of the following are true:

- [ ] `todo.md` has no remaining tasks
- [ ] All Spock integration tests pass (`./gradlew test`)
- [ ] The cluster is running in local minikube and the smoke check passes (leader elected per shard, key written and read back from a non-leader)
- [ ] All code is committed and pushed to GitHub with meaningful commit messages
- [ ] No hardcoded config values — all configuration is injected via `configmap.yaml`
- [ ] `BLOCKED.md` does not exist
- [ ] `progress.md` is complete with outcomes for every task

When all conditions are met, the coordinator writes a final `DONE.md` at the repo root summarising what was built, any known limitations, time taken per task, and total pipeline duration.

## Inter-Agent Contract

All handoffs use structured markdown blocks to prevent misreading. Any agent receiving a handoff must validate that all required fields are present before acting.

Coordinator → Coding Agent:
```
Task: <short title>
Goal: <one sentence>
Relevant docs: <comma-separated list>
Acceptance: <what the deployment agent should verify>
Attempt: <1-6>
```

Deployment Agent → Coordinator:
```
Status: <PASS|FAIL>
Task: <short title>
Details: <what was verified or what failed>
Logs: <relevant pod logs or kubectl output, if failed>
```
