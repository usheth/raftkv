# Agent Pipeline

## Overview

The project is built using a four-agent pipeline. A coordinator breaks the project into atomic tasks, a coding agent implements each one, a code review agent reviews the diff before it ships, and a deployment agent validates each increment against a local Kubernetes cluster.

```
Coordinator → [task] → Coding Agent → [diff] → Coordinator → [diff + docs] → Review Agent → [verdict] → Coordinator
                                                                                                                │
                                                                                         ┌──────────────────────┤
                                                                                         │ APPROVE              │ REQUEST_CHANGES / REJECT
                                                                                         ▼                      ▼
                                                                                Deployment Agent          re-issue / BLOCKED.md
                                                                                         │
                                                                                         ▼
                                                                                     Coordinator
```

**Key rule:** All transitions are gated by the coordinator. The coding agent hands its diff back to the coordinator, which then assembles the full review handoff (diff + relevant docs) and passes it to the review agent. Deployment only happens after an explicit `APPROVE` verdict. On deployment failure, the coordinator `git revert`s the approved commit before re-issuing the task.

The coordinator is **stateless between iterations**. Each coordinator session is short: read state from disk, decide the next action, write updated state to disk, exit. The loop is driven externally (by the user or a script), not by the coordinator's context. This keeps the coordinator's context window small regardless of how long the project runs.

## Coordinator Agent

**Responsibility:** On each invocation, read `progress.md` and `todo.md`, determine the next action, execute it, update state files, and exit. Do not hold state in memory that is not written to disk.

**Each invocation follows this logic:**
1. Read `docs/agents/` and state files (`progress.md`, `todo.md`)
2. If `todo.md` does not exist — run the orientation step (see below) and generate it, then exit
3. If the last review returned `REQUEST_CHANGES` and is within review retry budget — re-issue the task to the coding agent with the review issues as context
4. If the last deployment failed and is within deployment retry budget — `git revert` the approved commit, then re-issue the task to the coding agent with the failure context
5. If the last deployment passed — mark it done in `todo.md`, issue the next pending task
6. If `todo.md` has no pending tasks — verify the definition of done and write `DONE.md` if all conditions are met
7. Write all state changes to disk before exiting

**Orientation step (first invocation only):**
1. Run `scripts/preflight.sh` — if it fails, write `BLOCKED.md` describing which check failed and exit. Do not proceed.
2. Read all files in `docs/agents/`
3. Survey the existing codebase — what modules exist, what is already implemented, what is missing
4. Generate `todo.md` with the full ordered task list
5. Exit — do not issue the first task yet; that happens on the next invocation

This prevents creating tasks for work already done and ensures dependencies are understood before decomposition.

**What makes a task atomic:**
- Produces a runnable artifact (the cluster can be redeployed after it)
- Has a single clear goal (e.g. "implement leader election in the raft module")
- Is independent enough to be reviewed in isolation
- Completable in one coding session and produces a diff reviewable in under 5 minutes — if a task feels larger than this, split it

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

`todo.md` format:
```
## Pending
- [ ] <task title> — <one sentence goal>

## In Progress
- [ ] <task title> — <one sentence goal>

## Done
- [x] <task title>
```

`progress.md` entry format:
```
## <task title>
- Started: <timestamp>
- Review <n>: <APPROVE|REQUEST_CHANGES|REJECT> — <one sentence reason>
- Deployment <n>: <PASS|FAIL> — <one sentence reason>
- Completed: <timestamp>
- Time taken: <duration>
```
Every review verdict and deployment attempt must be logged. If a task required retries, the full sequence of events must be visible in the history.

**Retry budgets:** Review retries and deployment retries are tracked separately.
- A task may receive at most 6 `REQUEST_CHANGES` verdicts before the coordinator escalates to the user
- A task may fail deployment at most 6 times before the coordinator escalates to the user
- The two counters are independent — exhausting one does not affect the other

**Human escalation:** Escalation means writing a `BLOCKED.md` at the repo root describing the task, the failure, and what decision is needed from the user. The pipeline stops until `BLOCKED.md` is deleted.

**Open questions:** When a coding agent reports open questions, the coordinator must resolve them before issuing the next task. If a question cannot be resolved without user input, escalate via `BLOCKED.md`. Never let an open question carry forward silently — it may invalidate future tasks.

**Dependency re-evaluation:** After any retry that changes an interface or module boundary, the coordinator must re-read `todo.md` and assess whether downstream tasks are still valid before proceeding.

**Dependency versions:** After each task, verify that all Gradle dependency versions are pinned and consistent across modules. Flag any unpinned or conflicting versions as a follow-up task before moving on.

**On deployment failure:** The coordinator receives the deployment agent's report, determines if it's a code issue or infra issue, and either re-issues the task with the error as context or escalates to the user.

## Coding Agent

**Responsibility:** Receive a single task from the coordinator. Load only the `docs/agents/` files listed in the handoff. Implement the task. Do not implement beyond the task scope.

**Each task runs in a fresh agent session.** The coordinator must not reuse a coding agent across tasks. Context from previous tasks must not carry over.

**Rules:**
- Read existing code before modifying anything
- Do not add features not requested in the task
- Run `./gradlew test` before reporting done — do not hand off if tests fail
- Do not commit — the review agent decides whether the diff is committed
- When done, report back a summary of what changed and any open questions for the coordinator

## Code Review Agent

**Responsibility:** Receive the task handoff and the git diff from the coding agent. Review the diff and decide: approve (coordinator proceeds to deployment), request changes (coordinator re-issues to coding agent), or reject (coordinator escalates to user).

**Each review runs in a fresh agent session.**

**The coordinator passes the same `Relevant docs` list to the review agent as it passed to the coding agent.** The review agent must load these before reviewing — correctness and consistency can only be judged against the architectural decisions in those docs.

**Review checklist:**
- **Scope** — does the diff touch only files relevant to the task? Flag any out-of-scope changes
- **Correctness** — does the implementation match the goal in the task handoff and the decisions in the relevant docs?
- **Consistency** — does the code follow conventions established in the rest of the codebase?
- **Security** — flag any unsafe deserialization, command injection via config, or log injection risks
- **Tests** — are the changes covered by tests? New logic without tests is a change request
- **No hardcoded config** — all ports, addresses, and topology must come from config, not code

**Verdicts:**
- `APPROVE` — commit the diff and proceed to deployment
- `REQUEST_CHANGES` — return specific required changes to the coordinator; counts as a retry attempt
- `REJECT` — unresolvable issue; coordinator escalates to user via `BLOCKED.md`

On `APPROVE`, the review agent commits the diff with a meaningful message and reports done.

## Deployment Agent

**Responsibility:** After each coding task, build the Docker image, deploy to the local k8s cluster, and report status back to the coordinator.

**Steps:**
1. Run `./gradlew build` — fail fast if the build breaks
2. Build Docker image: `docker build -t raftkv:dev .`
3. Load into local cluster: see `docs/agents/deployment.md` for the cluster type
4. Apply manifests: `kubectl apply -f k8s/`
5. Wait for rollout: `kubectl rollout status deployment/raftkv-server`
6. Run smoke check: see `docs/agents/deployment.md` for the smoke check command
7. Write full logs to `logs/<task-title>.log`
8. Report status only (PASS/FAIL + one sentence) — the coordinator reads full logs from disk if needed for a retry

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

Coordinator → Review Agent:
```
Task: <short title>
Goal: <one sentence>
Relevant docs: <comma-separated list — same as passed to coding agent>
Diff: <output of git diff HEAD>
Changed files: <comma-separated list>
Open questions: <any unresolved questions from coding agent, or "none">
```

Review Agent → Coordinator:
```
Verdict: <APPROVE|REQUEST_CHANGES|REJECT>
Task: <short title>
Issues: <bulleted list of required changes, or "none">
```

Deployment Agent → Coordinator:
```
Status: <PASS|FAIL>
Task: <short title>
Details: <what was verified or what failed>
Logs: <see logs/<task-title>.log>
```
