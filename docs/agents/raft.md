# Raft Consensus

## Scope
- Leader election
- Log replication
- Membership changes via joint consensus (§6 of the Raft paper)
- Snapshots are out of scope

## Key Decisions

**Single-threaded event loop per node.** All state mutations go through one thread. Incoming gRPC calls and outbound RPC callbacks both submit tasks to this executor. This avoids locking complexity on Raft state.

**Injectable Clock.** Raft timers must use a `Clock` interface, not `System.currentTimeMillis()` directly. This is required for deterministic tests.

**Pure state machine in `raft` module.** The Raft engine has no networking or threading of its own — it accepts inputs (RPCs, timer ticks) and produces outputs (state transitions, RPCs to send). Transport and threading live in `server`.

**Persistent state.** `currentTerm`, `votedFor`, and the log must survive restarts. Use a simple append-only file per node.

## Reference
Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm" (2014)
https://raft.github.io/raft.pdf — §5 (core protocol), §6 (membership changes)
