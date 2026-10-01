# ADR 0005: Private bounded metrics and local performance evidence

Status: accepted, Phase 6.

Use a directly managed Micrometer Prometheus registry rather than exposing the full Actuator surface. This keeps the gateway's authenticated scrape endpoint narrow and avoids default URI tags or management capabilities. A separate monitoring credential authorizes only GET `/internal/metrics`; application credentials never gain monitoring access. Metrics use fixed label sets, excluding identities and operation IDs. PostgreSQL operation traces remain the tenant-scoped detailed evidence source.

Prometheus and Grafana are optional local Compose services with persistent named volumes and bounded memory/retention. Configuration, dashboard panels and alert expressions are versioned. Credentials live in private ignored host directories, whose 0700 permissions protect container-readable 0644 files. This is development credential mounting, not a public deployment secret store. No external alerts are sent.

Use bounded mock workloads against a dedicated benchmark tenant, preserving the demo policy. Report successful throughput separately from attempts, distinguish latency for success/rejection, collect resource samples and disclose client/component measurement boundaries. No Java/Go comparison or hosted generation performance is inferred. Retain framework choices until measurements justify changes.

Enable explicit graceful shutdown, a bounded HTTP admission semaphore and cancellation of interrupted outbound futures. Existing execution deadlines and shared Redis leases remain authoritative. Client disconnect, side effects after dispatch and pause/lease fencing remain documented limitations.
