# Project Structure

## Gradle Submodules

```
raftkv/
├── AGENTS.md
├── build.gradle
├── settings.gradle
├── docs/agents/
├── proto/          # Shared .proto definitions and generated sources
├── core/           # Shared domain types, no I/O (LogEntry, NodeId, Clock, RaftRole)
├── raft/           # Raft state machine, depends on core only
├── store/          # KV store and shard routing, depends on core
├── server/         # gRPC server, wires raft + store + transport
└── test-harness/   # Test infrastructure + Spock integration tests
```

## Key Constraints

- `raft` has no knowledge of networking or threading — those live in `server`
- `test-harness` is never imported by production modules
- Java 21, Gradle 8+
