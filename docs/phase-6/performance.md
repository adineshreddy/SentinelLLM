# Phase 6 — Local performance evidence

Measured 2026-10-01T14:27:49Z. Source: [metadata-only benchmark report](performance-v1.json).

## Workload and environment

Apple M5, 10 logical CPUs, 16 GiB host RAM, ARM64, macOS-26.6.2-arm64-arm-64bit-Mach-O. Docker Engine 29.6.1, Compose 5.3.0. Container runtimes: Temurin Java 21.0.12.1, Python 3.14.2, PostgreSQL 17.11, Redis 7.2.16, Prometheus 3.13.3 LTS, Grafana OSS 13.2.3. Gateway: Spring Boot 3.5.16, Java target 21, virtual threads enabled. Inspector classifier **off**; existing rules enabled. One gateway/inspector/MCP adapter with real PostgreSQL/Redis and monitoring enabled.

The generator ran 200 requests per workload/concurrency pair, after five warm-ups per pair: 1,800 measured attempts plus 45 warm-up operations. Preview inspects one benign password-reset prompt. Chat processes one short benign user message and deterministic mock output, inspecting input, assembled request and output. Tool calls use registered `kb.search` through the real Python MCP SDK bridge and inspect arguments/results. Payloads are synthetic and small; no hosted calls, streaming, long context, model inference or horizontal scaling was tested. The isolated benchmark tenant temporarily used a 1,000-request/one-second quota; security policy and execution concurrency stayed at their existing limits (provider 2, tools 4, inspection 8). Its previous policy was restored as a new revision.

A closed-loop Python thread pool uses fresh client connections. The stack had been warmed by reliability checks. This is one short run on a development laptop, with background readiness probes and ordinary host activity; no confidence interval or sustained-capacity conclusion is claimed. Dependencies use real durable state and append-only audit writes.

## Client latency and successful throughput

| Workload | Concurrency | Success / attempts | Successes/sec | Success p50 ms | Success p95 ms | Success p99 ms | 429s |
|---|---:|---:|---:|---:|---:|---:|---:|
| preview | 1 | 200/200 | 132.49 | 6.41 | 14.05 | 28.39 | 0 |
| preview | 2 | 200/200 | 224.81 | 7.24 | 14.52 | 39.08 | 0 |
| preview | 8 | 200/200 | 344.26 | 17.05 | 52.44 | 86.21 | 0 |
| chat | 1 | 200/200 | 105.14 | 8.59 | 13.17 | 32.49 | 0 |
| chat | 2 | 200/200 | 161.62 | 10.84 | 18.05 | 35.43 | 0 |
| chat | 8 | 26/200 | 93.18 | 25.91 | 36.51 | 37.49 | 174 |
| tool | 1 | 200/200 | 55.30 | 16.48 | 25.25 | 54.26 | 0 |
| tool | 2 | 200/200 | 79.88 | 24.25 | 31.36 | 37.73 | 0 |
| tool | 8 | 43/200 | 89.58 | 47.89 | 65.06 | 66.77 | 157 |

At concurrency 8, chat rejected 174/200 requests and tools rejected 157/200. These are admission outcomes, not completed generation/tool throughput. For chat, all-attempt p95 was 27.06 ms, while successful-request p95 was 36.51 ms; fast rejections can hide completion latency. No 5xx or transport failures occurred in the measured workloads. The full report includes attempted throughput and all-attempt percentiles.

## Component timing at concurrency 1

| Workload | Inspection ms/attempt | Provider ms/attempt | MCP tool ms/attempt | Audit ms/attempt | State ms/attempt | Redis ms/attempt | Unattributed client ms/attempt |
|---|---:|---:|---:|---:|---:|---:|---:|
| preview | 1.77 | 0.00 | 0.00 | 0.67 | 1.69 | 1.00 | 2.40 |
| chat | 2.73 | 0.02 | 0.00 | 1.99 | 1.03 | 1.06 | 2.66 |
| tool | 2.19 | 0.00 | 8.66 | 1.87 | 1.21 | 1.30 | 2.83 |

Component values are timer-sum deltas divided by **attempts**, including all serial calls in that category. For example, chat has three inspection calls per success. Provider time is deterministic mock construction and should not be interpreted as LLM latency. Unattributed time includes gateway policy/schema work, scheduling, serialization, lease release, client transport and measurement boundaries. State/Redis sums also include any readiness probes during the interval. No p95 subtraction is used. These timings separate observed dependency cost from the remaining client-observed time; they do not isolate Java CPU cost.

## Resource samples

Five `docker stats` snapshots were captured during the run; CPU is container utilization and may exceed 100% when using multiple cores. Memory is Docker working-set reporting, not JVM heap alone. Samples are sparse and cannot establish sustained peak usage.

| Container | Mean sampled CPU | Max sampled CPU | Max sampled memory | Limit during measurement |
|---|---:|---:|---:|---:|
| console | 5.83% | 29.12% | 20.2MiB | 192 MiB |
| gateway | 68.00% | 115.40% | 370.7MiB | 640 MiB |
| grafana | 0.86% | 1.49% | 211.3MiB | 256 MiB |
| inspection | 15.17% | 19.38% | 55.19MiB | 384 MiB |
| mcp-adapter | 27.60% | 50.32% | 66.49MiB | 256 MiB |
| postgres | 13.19% | 28.81% | 46.41MiB | 256 MiB |
| prometheus | 1.06% | 3.31% | 80.5MiB | 256 MiB |
| redis | 1.45% | 1.99% | 10.01MiB | 128 MiB |

After this short benchmark, repeated dashboard-query verification exhausted Grafana's initial 256-MiB limit and Docker reported `OOMKilled=true`. The final Compose configuration raises Grafana to **768 MiB**, sets `GOMEMLIMIT=256MiB`, adds an HTTP health check and restarts it unless explicitly stopped. The table above retains the actual limits used during the recorded run; it does not retrospectively change the benchmark. Repeated queries reached roughly 480 MiB working set, so the final 768-MiB limit provides headroom instead of keeping the container close to its ceiling. Subsequent dashboard queries were verified against the revised configuration.

The next useful performance work is sustained runs with larger contexts, opt-in ML comparison, multiple replicas, and an authorized hosted-provider latency study. Nothing in this evidence establishes that Java is faster or slower than Go.
