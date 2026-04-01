# Agent Pipeline

## Overview

The project is built using a four-agent pipeline. A coordinator breaks the project into atomic tasks, a coding agent implements each one, the coordinator spawns a review agent to review the diff, and a deployment agent validates each increment against a local Kubernetes cluster.

```
Coordinator → [task] → Coding Agent → DONE → Coordinator
                                                   │
                                         spawn Review Agent
                                                   │
                                     REQUEST_CHANGES │ APPROVE │ REJECT
                                           │                │       └──→ BLOCKED.md
                          re-issue to Coding Agent          ▼
                          (up to 5 rounds)         Deployment Agent
                                                           │
                                                   PASS    │    FAIL (git revert + re-issue)
                                                           ▼
                                                  git push + next task
```

**Key rule:** The coordinator owns all agent spawning. After issuing a task to the coding agent and receiving a `DONE` signal, the coordinator spawns a fresh review agent. If the review agent returns `REQUEST_CHANGES`, the coordinator re-issues the task to a fresh coding agent with the issues. The coordinator is the sole orchestrator — coding agents and review agents never spawn other agents. Deployment only happens after an explicit `APPROVE` verdict from the review agent.

The coordinator is **long-running**. It does not exit between tasks and requires no human involvement once started. After each successful deployment, the coordinator pushes the commit and immediately issues the next pending task without pausing. The pipeline runs autonomously from orientation through to `DONE.md`. The only circumstances under which the coordinator stops are: all tasks complete, or a `BLOCKED.md` is written because a problem genuinely requires human input.

**Agent lifetimes and spawning chain:** Every agent except the coordinator is short-lived — each is spawned for a single job and exits when done. The coordinator spawns: a fresh coding agent per task (and per review retry and per deployment retry); a fresh review agent per review round; a fresh deployment agent per deployment attempt. No state carries over between spawns; everything needed must be passed explicitly in the handoff.

**Coordinator context hygiene:** The coordinator must never open or read source code files (`.java`, `.groovy`, `.proto`, `.gradle`, `Dockerfile`, Kubernetes manifests, or any file under `src/`). It reads only its state files (`progress.md`, `todo.md`), the pipeline docs (`docs/agents/`), and plain-text status output from commands like `git status`. Reading diffs or code is the review agent's job. Violating this rule pollutes the coordinator's context and degrades pipeline reliability.

## Coordinator Agent

**Responsibility:** Run the full pipeline loop from start to finish. Read initial state from disk, execute orientation if needed, then iterate through every pending task — coding → review → deployment — without stopping between iterations. Write all state to disk continuously so the pipeline can be resumed after an unexpected exit.

**Full loop — run continuously until done or blocked:**
1. Read `docs/agents/` and state files (`progress.md`, `todo.md`) — do NOT read source code
2. If `todo.md` does not exist — run the orientation step (see below), then immediately continue to step 3
3. If there is a task `In Progress` with a deployment `FAIL` within budget — run `git revert HEAD` to undo the approved commit, re-issue the task to a fresh coding agent (with failure context); go to step 5
4. Pick the next `Pending` task, move it to `In Progress` in `todo.md`
5. **Coding step:** Issue the task to a fresh coding agent; wait for `DONE` signal:
   - If the coding agent reports open questions — resolve them from docs; if unresolvable without human input, write `BLOCKED.md` and stop
6. **Review step:** Spawn a fresh review agent with the task context (review round 1); wait for verdict:
   - `REQUEST_CHANGES` — re-issue the task to a fresh coding agent with the review issues and full review history; when coding agent signals `DONE` again, spawn a fresh review agent (next round); repeat up to 5 `REQUEST_CHANGES` rounds
   - `REJECT` — write `BLOCKED.md` and stop
   - `APPROVE` — log verdict in `progress.md`, continue to deployment step
7. **Deployment step:** Spawn a fresh deployment agent; wait for result; log result in `progress.md`
   - `FAIL` within budget — run `git revert HEAD`, return to step 5 with failure context
   - `FAIL` budget exhausted — write `BLOCKED.md` and stop
   - `PASS` — run `git push origin master`, mark task done in `todo.md`, update `progress.md`
8. Re-evaluate downstream tasks in `todo.md` for any interface or dependency changes
9. Verify all Gradle dependency versions are pinned; if not, insert a version-pinning task before proceeding
10. **Immediately go to step 4** — no pause, no human prompt, no wait
11. When no `Pending` tasks remain — verify the definition of done and write `DONE.md`

**Orientation step (first run only — skipped on resume):**
1. Run `scripts/preflight.sh` — if it fails, write `BLOCKED.md` describing which check failed and stop
2. Read all files in `docs/agents/`
3. Survey the existing codebase — what modules exist, what is already implemented, what is missing
4. Generate `todo.md` with the full ordered task list
5. Immediately continue into the pipeline loop — do not pause or wait for human input

On resume (coordinator restarted mid-pipeline), skip orientation entirely — `todo.md` already exists, so proceed directly to step 3 of the main loop.

**What makes a task atomic:**
- Produces a runnable artifact (the cluster can be redeployed after it)
- Has a single clear goal (e.g. "implement leader election in the raft module")
- Is independent enough to be reviewed in isolation
- Completable in one coding session and produces a diff reviewable in under 5 minutes — if a task feels larger than this, split it

**Task handoff format (Coordinator → Coding Agent):**
```
Task: <short title>
Goal: <one sentence>
Relevant docs: <list of docs/agents/ files to load>
Acceptance: <what the deployment agent should verify>
Deployment attempt: <1-6>
Failure context: <deployment failure details from previous attempt, or "none">
Review round: <1-6>
Review issues: <issues from review agent, or "none" on round 1>
Review history: <prior round verdicts and issues, oldest first — or "none" on round 1>
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
- Review: <APPROVED|REJECTED> after <n> round(s)
- Deployment <n>: <PASS|FAIL> — <one sentence reason>
- Completed: <timestamp>
- Time taken: <duration>
```

All timestamps must use the format `YYYY-MM-DD HH:MM:SS.mmm` (e.g. `2026-03-30 14:23:07.412`), including seconds and milliseconds. Time taken must be recorded in milliseconds (e.g. `Time taken: 47823ms`).
The coordinator logs the final review verdict and the number of rounds it took, plus every deployment attempt. If a task required deployment retries, each attempt must appear as a separate `Deployment <n>` line so the full sequence is visible in the history.

**Retry budgets:** Review retries and deployment retries are tracked separately.
- The review loop allows at most 5 `REQUEST_CHANGES` rounds per task; if the review agent issues a 6th `REQUEST_CHANGES` it must instead `REJECT`, which causes the coordinator to write `BLOCKED.md`
- A task may fail deployment at most 6 times before the coordinator writes `BLOCKED.md`
- The two counters are independent — exhausting one does not affect the other

**Human escalation:** The only reason the pipeline stops is a genuine blocker that cannot be resolved from the codebase or docs alone. Escalation means writing a `BLOCKED.md` at the repo root describing the task, the failure, and what decision is needed. The pipeline stops until `BLOCKED.md` is deleted. Do not escalate for things that can be resolved by re-reading docs, adjusting the implementation, or retrying within budget.

**Open questions:** When the coding agent's `DONE` report includes open questions, the coordinator must resolve them before spawning the review agent. If a question can be answered from `docs/agents/`, answer it inline. Only escalate via `BLOCKED.md` if the answer genuinely requires human input unavailable in the docs.

**Dependency re-evaluation:** After any retry that changes an interface or module boundary, the coordinator must re-read `todo.md` and assess whether downstream tasks are still valid before proceeding.

**Dependency versions:** After each task, verify that all Gradle dependency versions are pinned and consistent across modules. Flag any unpinned or conflicting versions as a follow-up task before moving on.

**On deployment failure:** The coordinator receives the deployment agent's report, runs `git revert HEAD` to undo the approved commit, determines if it's a code issue or infra issue, and either re-issues the task with the error as context or (only if infra is broken and unrecoverable without human help) writes `BLOCKED.md`.

## Coding Agent

**Responsibility:** Receive a single task from the coordinator. Load only the `docs/agents/` files listed in the handoff. Implement the task. Signal `DONE` to the coordinator when finished. Do not spawn other agents. Do not implement beyond the task scope.

**Each task runs in a fresh agent session.** The coordinator must not reuse a coding agent across tasks. Context from previous tasks must not carry over.

**Rules:**
- Read existing code before modifying anything
- Do not add features not requested in the task
- Run `./gradlew test` before signalling `DONE` — do not signal done if tests fail
- Do not commit — the review agent commits on `APPROVE`
- If `Review round` is greater than 1, address all issues listed in `Review issues` before signalling `DONE`
- Signal `DONE` to the coordinator with any open questions; the coordinator will handle review

## Code Review Agent

**Responsibility:** Receive the task context from the coordinator. Run `git diff HEAD` to obtain the diff. Review the diff and decide: approve, request changes, or reject. Report the verdict back to the coordinator.

**Each review round is a fresh agent session.** Because no state carries over, the coordinator passes the full review history (all prior round verdicts and issues) in every handoff so the review agent can apply consistent standards and not re-flag already-resolved issues.

**The coordinator passes the same `Relevant docs` list it gave the coding agent.** The review agent must load these before reviewing — correctness and consistency can only be judged against the architectural decisions in those docs.

**Review checklist:**
- **Scope** — does the diff touch only files relevant to the task? Flag any out-of-scope changes
- **Correctness** — does the implementation match the goal in the task handoff and the decisions in the relevant docs?
- **Consistency** — does the code follow conventions established in the rest of the codebase?
- **Security** — flag any unsafe deserialization, command injection via config, or log injection risks
- **Tests** — are the changes covered by tests? New logic without tests is a change request
- **No hardcoded config** — all ports, addresses, and topology must come from config, not code

**Verdicts:**
- `APPROVE` — commit the diff first, then report `APPROVE` to the coordinator; committing before notifying ensures the coordinator never proceeds to deployment against an uncommitted diff
- `REQUEST_CHANGES` — return specific required changes to the coordinator for re-issuance to the coding agent; if this would be the 6th round, issue `REJECT` instead
- `REJECT` — unresolvable issue; the coordinator writes `BLOCKED.md`

## Deployment Agent

**Responsibility:** After each coding task, build the Docker image, deploy to the local k8s cluster, and report status back to the coordinator.

**Steps:**
1. Run `./gradlew build` — fail fast if the build breaks
2. Build Docker image: `docker build -t raftkv:dev .`
3. Load into local cluster: see `docs/agents/deployment.md` for the cluster type
4. Apply manifests: `kubectl apply -f k8s/`
5. Wait for rollout: `kubectl rollout status statefulset/raftkv-server -n raftkv`
6. Run smoke check: see `docs/agents/deployment.md` for the smoke check command
7. `mkdir -p logs` then write full logs to `logs/<task-title>.log`
8. Report status only (PASS/FAIL + one sentence) — the coordinator reads full logs from disk if needed for a retry

**On failure:** Roll back to the last known good image (`kubectl rollout undo statefulset/raftkv-server -n raftkv`) to restore the cluster before reporting. Then report the full failure context to the coordinator. Do not attempt to fix code.

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
Deployment attempt: <1-6>
Failure context: <deployment failure details from previous attempt, or "none">
Review round: <1-6>
Review issues: <issues from review agent, or "none" on round 1>
Review history: <prior round verdicts and issues, oldest first — or "none" on round 1>
```

Coding Agent → Coordinator:
```
Outcome: DONE
Task: <short title>
Open questions: <any unresolved questions for the coordinator, or "none">
```

Coordinator → Review Agent:
```
Task: <short title>
Goal: <one sentence>
Relevant docs: <comma-separated list>
Acceptance: <what the deployment agent should verify>
Review round: <1-6>
Review history: <prior round verdicts and issues, oldest first — or "none" on round 1>
```

Review Agent → Coordinator:
```
Verdict: <APPROVE|REQUEST_CHANGES|REJECT>
Task: <short title>
Round: <1-6>
Issues: <bulleted list of required changes, or "none">
```

Deployment Agent → Coordinator:
```
Status: <PASS|FAIL>
Task: <short title>
Details: <what was verified or what failed>
Logs: <see logs/<task-title>.log>
```
