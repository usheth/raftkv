# Transport

## Scope
- gRPC for all node-to-node and client-to-node communication
- Two services: `RaftService` (internal, node-to-node) and `KvService` (external, client-facing)

## Key Decisions

**Proto definitions in a shared `proto/` module.** Generated sources are shared across modules via `protobuf-gradle-plugin`. No module duplicates proto definitions.

**Fault injection via gRPC interceptors.** `RaftTransportImpl` must accept injectable `ClientInterceptor`s so the test harness can intercept RPCs per-link. This is the mechanism for network partition simulation — do not bypass it.

**Ephemeral ports in tests.** Servers bind to port `0` in tests. `RaftServer.getPort()` returns the actual bound port after start.
