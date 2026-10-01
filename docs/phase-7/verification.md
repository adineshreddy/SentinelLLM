# Phase 7 verification

Local validation on 2026-10-01: macOS ARM64, Apple M5, Docker 29.6.1 / Compose 5.3.0, approximately 7.75 GiB allocated to Docker. Hosted CI uses Ubuntu 24.04 AMD64 with disposable mock-only infrastructure.

| Check | Result |
|---|---|
| Existing Java/Python/setup/BFF checks | 139 passed locally |
| Contracts | 21 schemas, 19 valid / 7 invalid examples, 14 desired-behavior fixtures passed |
| Terraform | fmt and validate passed; provider lock includes darwin_arm64/linux_amd64 |
| Workflow | actionlint 1.7.12 and offline deployment invariants passed |
| Deployment | 45 resources created with optional monitoring; all workloads ready; migration Job completed |
| Security smoke | Mock chat, injection denial, PII redaction, poisoned RAG denial, allowed/denied tools, structured output, tenant-scoped evidence, console login/logout passed |
| Monitoring | Authenticated gateway scrape target healthy |
| NetworkPolicy | Five allowed internal paths and five denied internal paths passed; DNS allowed; external egress blocked with a successful unrestricted control probe |
| Recovery | PostgreSQL, Redis and gateway Pod replacement passed; prior completed operation retained |
| Terraform change | Plan/apply changed gateway replicas 1 → 2; two ready replicas passed smoke checks; restored to 1 |
| Secret/state boundary | Actual state and backups scanned; no configured credential values; generated credentials/identity hashes excluded from staged source |
| Teardown | Terraform destroyed all 45 resources; kind removed only the dedicated cluster |
| Clean recreation | Passed: bootstrap, image load, 45-resource apply, smoke/monitoring checks and real network checks repeated after full teardown |
| Hosted CI | First Kubernetes job passed; application job passed 25/26 Compose checks and exposed an immediate-scrape timing race. Bounded successful-scrape wait added; full rerun pending. |

The network checker separates Service workload labels from probe role labels so test Pods never become live Service endpoints. It fails if the positive external control cannot connect; a generally broken network is not accepted as evidence of external-egress enforcement. Recovery deletes runtime Pods, preserving Terraform-owned controllers/PVCs. No hosted model calls or cloud provisioning have been performed. Compose volumes remain independent and untouched.

Initial validation caught and corrected explicit container non-root settings, a mock fixture default mismatch, configuration-change rollout behavior, and separation of network probe Pods from Service endpoints. No security checks were bypassed to obtain a passing result.

The public repository is [adineshreddy/SentinelLLM](https://github.com/adineshreddy/SentinelLLM). Single-node kind tests demonstrate local deployment and isolation; they do not establish production high availability, cloud deployment or a new performance result.
