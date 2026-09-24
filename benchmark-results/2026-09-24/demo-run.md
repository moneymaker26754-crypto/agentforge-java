# Real CLI repair demo

- Session: `df8eb363-caea-45e7-b164-df1022595cfe`
- Provider/model: DeepSeek / `deepseek-flash`
- Sandbox: Docker, network disabled, UID 1000, 2 CPU, 4GB, 256 PID
- Task: fix `Calculator.add` without changing its public API; compile and run `CalculatorTest`
- Outcome: `COMPLETED`; `PASS CalculatorTest`
- Change: `return left - right` → `return left + right`
- Model responses: 6
- Tool calls: 7, all successful (4 read/list operations, 1 patch, 2 process executions)
- Human approvals: 3 (patch, compile, test)
- Usage: 8,332 input tokens; 641 output tokens
- Estimated cost: ¥0.021792 using the 2026-09-24 conservative peak cache-miss snapshot
- Wall time: about 101.3 seconds, including about 91 seconds waiting for manual approval input

The SQLite audit stream contains 28 ordered events (`0..27`): session start, six model responses, seven intent/result pairs, three approval request/decision pairs, and session completion. The demo workspace is intentionally ignored and is not part of the repository deliverable.
