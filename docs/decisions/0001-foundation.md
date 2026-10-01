# Foundation architecture decisions

**Status:** Accepted for Phase 0  
**Date:** September 30, 2026

## ADR-001 — Java gateway and Python inspection

Use a modular Spring Boot service for authentication, policy, schema checks, routing, and management. Use an internal FastAPI service for detectors and later local ML inference. Java matches the target role and offers a mature backend/security ecosystem. Python fits ML experimentation.

Tradeoff: Java's baseline memory/startup and a cross-service hop are higher than a small single-binary gateway. Bound the HTTP interface, fail closed, and measure overhead. Do not implement policy authorization in both services. Go is not part of the required stack.

## ADR-002 — Mock default with configurable NaviGator adapter

Use deterministic mocks for tests, load tests, and repeatable demo scenarios. Use UF NaviGator for opt-in real generation, starting with configurable `gpt-oss-120b`. Confirm its account allowance before live use. Local Ollama is optional.

Tradeoff: mocks cannot prove live model compatibility; opt-in smoke tests must verify that separately. Hosted API availability is not required for core development.

## ADR-003 — Buffered text subset first

Release no provider output until it has been inspected. Start with non-streaming text chat. Reject unsupported options explicitly rather than claiming full OpenAI compatibility. Tool proposals are inert until the separate executor approves them.

Tradeoff: buffering adds latency and memory use, managed with strict size limits. Streaming and multimodal content require new release/inspection contracts.

## ADR-004 — Declarative trusted policy, deny first

Bind immutable policy versions to authenticated applications. Use registered schemas/destinations and explicit role permissions. Deny wins over redaction. Restrict the initial schema vocabulary to bounded basic types and local structures; no remote references or arbitrary policy code.

Tradeoff: less flexibility than a full external policy language, but smaller attack surface and easier reasoning. Add richer authorization when concrete requirements justify it.

## ADR-005 — Metadata-only audit and phased durability

Use sanitized JSONL events in the single-process development profile. Phase 3 adds database-backed pre-operation records and outcome tracking. Raw payload retention is disabled; no prompt hashes or credential values enter audit.

Tradeoff: less debugging context and a deliberate availability cost on mandatory audit failure. Phase 1 logs are not tamper-proof or transactionally durable. Do not claim exactly-once tool execution.

## ADR-006 — Free local deployment, Terraform included

Compose is the main development interface. kind bootstraps a local Kubernetes cluster; Terraform Community manages workloads in the cluster during Phase 7. One resource has one owner; no simultaneous Terraform/manifest mutation.

Tradeoff: local deployment does not prove cloud IAM, managed networking, multi-zone availability, or cloud operations. Cloud provisioning is optional and its resource cost is separate from Terraform CLI cost.

## ADR-007 — UTF-8 location contract

Detection spans use half-open original-text UTF-8 byte offsets, avoiding Python code-point versus Java UTF-16 ambiguity. Reject malformed/misaligned spans. Preserve normalized-to-original mapping for any normalization-based redaction.

Tradeoff: conversion logic requires explicit Unicode tests, but the shared representation is unambiguous.
