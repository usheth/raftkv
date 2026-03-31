# Sharding

## Scope
- Consistent hashing to partition keys across multiple independent Raft groups
- Dynamic resharding (adding/removing shards while live) is in scope

## Key Decisions

**Virtual nodes.** Use vnodes (default 256) mapped to physical shards via a hash ring. This allows smooth rebalancing without rehashing all keys.

**MurmurHash3.** Use Guava's `Hashing.murmur3_128()` for key hashing.

**Config shard.** Shard 0 is a metadata shard. All hash ring mutations (vnode reassignments) go through its Raft log. This keeps ring state consistent across all routers without gossip complexity.

**Fencing during migration.** When a vnode is being migrated, the source shard rejects writes to that vnode with a `RESHARDING` error. Clients back off and retry. Migration is: fence → export → transfer → update ring → purge source.

**NOT_LEADER redirect.** If a node receives a client request but is not the leader, it returns `NOT_LEADER` with a leader hint. The client retries against the hint. `ShardRouter` handles this transparently.
