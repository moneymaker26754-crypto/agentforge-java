# AgentForge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Build, verify, document, and publish the AgentForge Java CLI coding agent.

**Architecture:** A four-module Maven modular monolith with a pure-Java core, infrastructure adapters, Spring/Picocli CLI, and deterministic evaluation module.

**Tech Stack:** Java 21, Maven, Spring Boot, Picocli, Jackson, SQLite JDBC, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-24-agentforge-design.md`

## Global Constraints

- Never write secrets to source, logs, reports, or commits.
- Docker is the default sandbox; local mode is explicit and argv-only.
- DeepSeek evaluation has a hard aggregate CNY 50 cap.
- Benchmark failures remain in the report and are never replaced.
- Production behavior follows RED-GREEN-REFACTOR.

## Review Focus

- Fragmented and malformed streamed tool calls must not execute partial data.
- Canonical and symlinked paths must not escape the selected workspace.
- Resume must not silently repeat uncertain non-idempotent work.
- Token, wall-time, step, repeated-call, and cost budgets must all terminate.
- Missing Docker, Ollama, API key, or benchmark infrastructure must be reported, not disguised as agent failure.

### Task 1: Core contracts and deterministic agent loop

- [ ] Test and implement domain records, budget accounting, context compaction, state transitions, and termination rules.
- [ ] Test and implement a scripted-model end-to-end loop.
- [ ] Verify with `mvn -pl agentforge-core -am test` and commit.

### Task 2: Streaming provider adapters

- [ ] Test fragmented SSE and NDJSON parsing, multiple tool calls, malformed frames, usage, and error mapping.
- [ ] Implement DeepSeek and Ollama model clients behind `ModelClient`.
- [ ] Verify with `mvn -pl agentforge-infrastructure -am test` and commit.

### Task 3: Tool registry, schemas, policy, and workspace tools

- [ ] Test annotation discovery, schema generation, binding, validation, policy decisions, and path confinement.
- [ ] Implement file, search, patch, process, and Git tools.
- [ ] Verify infrastructure tests and commit.

### Task 4: Checkpoint, audit, and recovery

- [ ] Test hash-chain verification, snapshots, replay, completed-call deduplication, and uncertain intents.
- [ ] Implement SQLite checkpoint storage and sanitized JSONL export.
- [ ] Verify infrastructure tests and commit.

### Task 5: Docker/local execution and CLI

- [ ] Test command construction, local allowlists, diagnostics, run/resume/session/report command contracts.
- [ ] Implement Spring Boot/Picocli wiring and executable packaging.
- [ ] Verify CLI tests and smoke commands, then commit.

### Task 6: Evaluation and generated reports

- [ ] Test the 30-scenario manifest, fixed Java-20 selection, budget stop, aggregation, percentiles, and README rendering.
- [ ] Implement micro and external benchmark runners without inventing results.
- [ ] Verify eval tests and commit.

### Task 7: Documentation and CI

- [ ] Add README, technical handbook, 120-question interview guide, resume story, architecture diagrams, and honest limitations.
- [ ] Add Maven wrapper, GitHub Actions, formatting/static checks, Docker sandbox image, examples, and benchmark manifests.
- [ ] Run `mvn verify`, CLI smoke tests, and Docker diagnostics; commit.

### Task 8: Evaluation evidence and publication

- [ ] Run available deterministic/live evaluations within the approved budget and generate reports.
- [ ] Perform whole-branch review, fix important findings, rerun verification, merge to main.
- [ ] Create the public GitHub repository and push `main`.

