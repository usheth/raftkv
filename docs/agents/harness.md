# Test Harness

## Scope
- Integration tests only, written in Groovy + Spock
- Tests live in `test-harness/src/test/groovy/`

## Key Decisions

**`ClusterHarness` owns the cluster lifecycle.** It creates nodes, starts them, and tears them down. It must implement `AutoCloseable` so Spock's `cleanup:` block handles teardown reliably even on test failure.

**Fault injection via gRPC interceptors.** The harness controls a per-link interceptor on every outbound channel. This gives tests the ability to partition, delay, or drop messages between specific node pairs. The API should feel like: `harness.partition(a, b)`, `harness.heal(a, b)`.

**No `Thread.sleep()` in tests.** The harness must provide convergence helpers (`awaitLeader()`, `awaitCommitIndex()`, etc.) that poll with a timeout. Tests block on these, not on arbitrary sleeps.

**`ManualClock` for time-sensitive tests.** When a test needs to trigger an election timeout deterministically, it uses a `ManualClock` that only advances when the test calls `clock.advance(ms)`.

**Spock `given/when/then`.** Tests should read as a narrative. The harness API should be expressive enough that setup is in `given:`, the action under test is in `when:`, and assertions are in `then:` — without boilerplate leaking across blocks.
