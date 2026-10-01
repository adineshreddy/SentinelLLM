# ADR 0002 — Durable tenant state and shared admission

Accepted for Phase 3.

Use PostgreSQL as the policy/registry and operation/audit authority, with Redis for atomic admission. Keep Java as the final authorization and release authority. Default Compose runs the persistent mode; memory mode remains an explicit native-test option and cannot be activated by a dependency outage.

Store policy and registry together under one tenant revision. Activate a revision with a row lock and optimistic expected-revision comparison, and record its actor in the same transaction. Read one snapshot per work request to prevent mixed decisions during updates. Preserve old revisions and restore by publishing a new one.

Use a separate migration container with database-owner credentials. The runtime role has row-level security plus explicit tenant predicates, append-only permissions for versions/events, and narrowly required updates for operation status and active heads. The application sets tenant context transaction-locally; database administrators remain trusted.

Commit decisions and dispatch intent before external work, and final outcome metadata before releasing success. Do not hold database transactions across external calls. Pending or ambiguous work remains visible and is never automatically repeated. Exactly-once execution and transactional coupling to external providers are not claimed; only read-only demo tools are supported.

Use Redis server time and Lua for shared fixed-window quotas and expiring concurrency leases. Revisions/key rotation do not clear quotas. Keep local capacity ceilings as an additional process bound. Leases do not fence a paused process that outlives its expiry; production coordination and recovery remain further work.

Management credentials and routes are tenant-scoped. Viewers read; operators publish validated revisions. Management policy edits cannot onboard arbitrary executors, remote URLs, or unreviewed schemas in this phase. MCP schema pinning remains in force.

This favors auditable correctness over cached configuration reads or batching. Phase 6 will measure database/inspection/admission overhead before optimizing it. The Phase 4 console can build on paginated events, operation traces, and immutable revision history.
