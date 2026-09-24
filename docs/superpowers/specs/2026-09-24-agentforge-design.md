# AgentForge Java CLI Coding Agent Design

AgentForge is a Java 21 command-line coding agent intended to demonstrate
backend and agent-engineering depth. It operates on one local repository,
streams model output, validates and authorizes tool calls, checkpoints every
observable transition, and produces reproducible run reports.

The approved architecture is a four-module Maven modular monolith. The core
module owns a deterministic state machine and has no Spring dependency.
Infrastructure supplies DeepSeek SSE and Ollama NDJSON adapters, annotated
tools, SQLite event storage, and Docker/local sandboxes. The CLI module wires
the application with Spring Boot and Picocli. The evaluation module owns
deterministic micro-scenarios and the pinned Java benchmark manifest.

Model-produced class names are never loaded. Spring creates trusted
`@AgentTool` beans, the registry reflects over their record argument types,
generates a constrained JSON Schema, validates arguments, binds with Jackson,
and injects an execution context. Read-only calls may run concurrently;
mutations are serialized.

Docker is the default executor: non-root, no network, 2 CPUs, 4 GiB memory,
256 PIDs, bounded output, and bounded wall time. Local execution requires an
explicit flag and argv-form commands. Canonical path and symlink checks keep
every file operation in the workspace. Policy decisions are ALLOW, ASK, or
DENY for READ, WRITE, EXECUTE, NETWORK, and DESTRUCTIVE risks.

SQLite stores an append-only, SHA-256-linked session event stream plus
periodic snapshots. Resume replays verified events. A non-idempotent tool with
an intent but no completion event becomes UNCERTAIN and is never silently
repeated. Raw events remain intact when the model-facing context is compressed.

The README must not contain invented results. A fixed Java benchmark subset,
30 deterministic micro-scenarios, and a fixed ablation subset emit sanitized
JSON/CSV and generate the README result table. Negative and infrastructure
failures remain visible. Paid DeepSeek evaluation is capped at CNY 50.

