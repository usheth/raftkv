# Agent Pipeline

## Overview

The project is built using a four-agent pipeline. A coordinator breaks the project into atomic tasks, a coding agent implements each one, a review agent works directly with the coding agent to iterate until the code is approved, and a deployment agent validates each increment against a local Kubernetes cluster.

```
Coordinator → [task] → Coding Agent ←──────────────────────────────────┐
                                    │                                    │ REQUEST_CHANGES (up to 5x)
                                    └──→ [diff + docs] → Review Agent ──┘
                                                                │
                                                        APPROVE │ REJECT
                                                                │       └──→ BLOCKED.md
                                                                ▼
                                                       Deployment Agent
                                                                │
                                                        PASS    │    FAIL (git revert + re-issue)
                                                                ▼
                                                          Coordinator
                                                       (next task, no pause)
```

**Key rule:** The coordinator issues a task and then the coding agent and review agent iterate together in a direct loop (max 5 `REQUEST_CHANGES` rounds) until the review agent either `APPROVE`s or `REJECT`s. The coordinator is not involved during the coding–review loop. Deployment only happens after an explicit `APPROVE` verdict. On deployment failure, the coordinator `git revert`s the approved commit before re-issuing the task to the coding agent.

The coordinator is **long-running**. It does not exit between tasks and requires no human involvement once started. After each successful deployment, the coordinator immediately issues the next pending task without pausing. The pipeline runs autonomously from orientation through to `DONE.md`. The only circumstances under which the coordinator stops are: all tasks complete, or a `BLOCKED.md` is written because a problem genuinely requires human input.

**Agent lifetimes and spawning chain:** Every agent except the coordinator is short-lived — each is spawned for a single job and exits when done. The spawning chain is: the coordinator spawns a fresh coding agent per task (and per deployment retry); the coding agent spawns a fresh review agent per review round; the coordinator spawns a fresh deployment agent per deployment attempt. No state carries over between spawns; everything needed must be passed explicitly in the handoff.

## Coordinator Agent

**Responsibility:** Run the full pipeline loop from start to finish. Read initial state from disk, execute orientation if needed, then iterate through every pending task — coding → review → deployment — without stopping between iterations. Write all state to disk continuously so the pipeline can be resumed after an unexpected exit.

**Full loop — run continuously until done or blocked:**
1. Read `docs/agents/` and state files (`progress.md`, `todo.md`)
2. If `todo.md` does not exist — run the orientation step (see below), then immediately continue to step 3
3. If there is a task `In Progress` with a deployment `FAIL` within budget — run `git revert HEAD` to undo the approved commit, re-issue the task to the coding agent (with failure context); go to step 5
4. Pick the next `Pending` task, move it to `In Progress` in `todo.md`
5. **Coding + review loop:** Issue the task to the coding agent with the task handoff; the coding agent works with the review agent directly (up to 5 `REQUEST_CHANGES` rounds). The coordinator waits for the final outcome from this loop:
   - If the coding agent reports open questions — resolve them from docs; if unresolvable without human input, write `BLOCKED.md` and stop
   - `REJECT` from the review agent → write `BLOCKED.md` and stop
   - `APPROVE` from the review agent → log verdict in `progress.md`, continue to deployment step
6. **Deployment step:** Issue to the deployment agent; wait for result; log result in `progress.md`
   - `FAIL` within budget → `git revert`, return to step 5 with failure context
   - `FAIL` budget exhausted → write `BLOCKED.md` and stop
   - `PASS` → mark task done in `todo.md`, update `progress.md`, run `git push`
7. Re-evaluate downstream tasks in `todo.md` for any interface or dependency changes
8. Verify all Gradle dependency versions are pinned; if not, insert a version-pinning task before proceeding
9. **Immediately go to step 4** — no pause, no human prompt, no wait
10. When no `Pending` tasks remain — verify the definition of done and write `DONE.md`

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

**Task handoff format:**
```
Task: <short title>
Goal: <one sentence>
Relevant docs: <list of docs/agents/ files to load>
Acceptance: <what the deployment agent should verify>
Deployment attempt: <1-6>
Failure context: <deployment failure details from previous attempt, or "none">
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
- The coding–review loop allows at most 5 `REQUEST_CHANGES` rounds per task; if the review agent issues a 6th `REQUEST_CHANGES` it must instead `REJECT`, which causes the coordinator to write `BLOCKED.md`
- A task may fail deployment at most 6 times before the coordinator writes `BLOCKED.md`
- The two counters are independent — exhausting one does not affect the other

**Human escalation:** The only reason the pipeline stops is a genuine blocker that cannot be resolved from the codebase or docs alone. Escalation means writing a `BLOCKED.md` at the repo root describing the task, the failure, and what decision is needed. The pipeline stops until `BLOCKED.md` is deleted. Do not escalate for things that can be resolved by re-reading docs, adjusting the implementation, or retrying within budget.

**Open questions:** When the coding agent's final outcome report includes open questions, the coordinator must resolve them before issuing the next task. If a question can be answered from `docs/agents/`, answer it inline. Only escalate via `BLOCKED.md` if the answer genuinely requires human input unavailable in the docs.

**Dependency re-evaluation:** After any retry that changes an interface or module boundary, the coordinator must re-read `todo.md` and assess whether downstream tasks are still valid before proceeding.

**Dependency versions:** After each task, verify that all Gradle dependency versions are pinned and consistent across modules. Flag any unpinned or conflicting versions as a follow-up task before moving on.

**On deployment failure:** The coordinator receives the deployment agent's report, runs `git revert HEAD` to undo the approved commit, determines if it's a code issue or infra issue, and either re-issues the task with the error as context or (only if infra is broken and unrecoverable without human help) writes `BLOCKED.md`.

## Coding Agent

**Responsibility:** Receive a single task from the coordinator. Load only the `docs/agents/` files listed in the handoff. Implement the task. Then hand off directly to the review agent. If the review agent returns `REQUEST_CHANGES`, address the issues and re-submit — repeat up to 5 times. Do not implement beyond the task scope.

**Each task runs in a fresh agent session.** The coordinator must not reuse a coding agent across tasks. Context from previous tasks must not carry over.

**Rules:**
- Read existing code before modifying anything
- Do not add features not requested in the task
- Run `./gradlew test` before handing off to the review agent — do not hand off if tests fail
- Do not commit — the review agent commits on `APPROVE`
- After receiving `REQUEST_CHANGES`, address all listed issues, re-run `./gradlew test`, and re-submit to the review agent
- If the review agent `APPROVE`s or `REJECT`s, or after 5 `REQUEST_CHANGES` rounds, report the final outcome back to the coordinator along with any open questions

## Code Review Agent

**Responsibility:** Receive the task handoff and the git diff from the coding agent. Review the diff and decide: approve (coding agent reports back to coordinator), request changes (coding agent fixes and resubmits), or reject (coding agent reports `REJECT` to coordinator).

**Each review round is a fresh agent session.** Because no state carries over, the coding agent must include the full review history (all prior round verdicts and issues) in every handoff so the review agent can apply consistent standards and not re-flag already-resolved issues.

**The coding agent passes the same `Relevant docs` list that the coordinator provided.** The review agent must load these before reviewing — correctness and consistency can only be judged against the architectural decisions in those docs.

**Review checklist:**
- **Scope** — does the diff touch only files relevant to the task? Flag any out-of-scope changes
- **Correctness** — does the implementation match the goal in the task handoff and the decisions in the relevant docs?
- **Consistency** — does the code follow conventions established in the rest of the codebase?
- **Security** — flag any unsafe deserialization, command injection via config, or log injection risks
- **Tests** — are the changes covered by tests? New logic without tests is a change request
- **No hardcoded config** — all ports, addresses, and topology must come from config, not code

**Verdicts:**
- `APPROVE` — commit the diff first, then notify the coding agent (who reports back to the coordinator); committing before notifying ensures the coordinator never proceeds to deployment against an uncommitted diff
- `REQUEST_CHANGES` — return specific required changes to the coding agent for another round; if this would be the 6th round, issue `REJECT` instead
- `REJECT` — unresolvable issue; coding agent reports this back to the coordinator who writes `BLOCKED.md`

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
```

Coding Agent → Review Agent:
```
Task: <short title>
Goal: <one sentence>
Relevant docs: <comma-separated list — same as provided by coordinator>
Diff: <output of git diff HEAD>
Changed files: <comma-separated list>
Review round: <1-6>
Review history: <prior round verdicts and issues, oldest first — or "none" on round 1>
Open questions: <any unresolved questions, or "none">
```

Review Agent → Coding Agent:
```
Verdict: <APPROVE|REQUEST_CHANGES|REJECT>
Task: <short title>
Round: <1-6>
Issues: <bulleted list of required changes, or "none">
```

Coding Agent → Coordinator (final outcome):
```
Outcome: <APPROVED|REJECTED>
Task: <short title>
Review rounds: <number of REQUEST_CHANGES rounds before final verdict>
Open questions: <any unresolved questions for the coordinator, or "none">
```

Deployment Agent → Coordinator:
```
Status: <PASS|FAIL>
Task: <short title>
Details: <what was verified or what failed>
Logs: <see logs/<task-title>.log>
```
