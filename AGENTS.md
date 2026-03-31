# raftkv

A distributed key-value store with Raft consensus replication and consistent hashing sharding.

## Project Overview

- **Language:** Java
- **Build:** Gradle
- **Transport:** gRPC
- **Tests:** Groovy + Spock (integration tests)

## Architecture Index

| Topic | Where to look |
|---|---|
| Raft consensus (election, replication, membership) | `docs/agents/raft.md` |
| Sharding and consistent hashing | `docs/agents/sharding.md` |
| gRPC transport layer | `docs/agents/transport.md` |
| Test harness design | `docs/agents/harness.md` |
| Module and package layout | `docs/agents/structure.md` |

## Key Constraints

- Each shard is backed by its own independent Raft group
- gRPC interceptors are used for fault injection in tests — do not bypass them
- Raft timers must use an injectable `Clock` interface, not `System.currentTimeMillis()` directly
- Integration tests must not use `Thread.sleep()` — use harness convergence helpers instead

## Where to Start

If you are new to this codebase, read `docs/agents/structure.md` first for the module layout, then the doc for the area you are working in.
