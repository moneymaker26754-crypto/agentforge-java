# 评测与复现

## 评测口径

`micro` 是 Agent 基础设施不变量测试；`java20` 是真实仓库 Issue 解决评测，两者不能合并成一个“准确率”。所有报告保留 manifest、逐题 JSON 与 CSV。`FAILED` 表示 Agent/测试失败，`ENVIRONMENT_FAILED` 表示 Docker、镜像、依赖或 harness 未就绪。

## 30 项微基准

```bash
./mvnw -q package
java -jar agentforge-cli/target/agentforge-cli-0.1.0-SNAPSHOT.jar \
  eval run --suite micro --profile full --output build/reports/agentforge
```

产物：

- `micro-full-results.json`：manifest、汇总与逐项结果。
- `micro-full-results.csv`：便于表格分析的原始行。
- `micro-full-manifest.json`：数据版本、profile、case id 与费用上限。

## Java20 固定样本

数据集：`SWE-bench/SWE-bench_Multilingual`，revision `846e647b9f33c0b51b739d005d13d85493c9af09`。Java 仓库为 Apache Druid、Apache Lucene、Gson、JavaParser、Lombok、RxJava；对 43 个 Java instance_id 排序取前 20，清单在 `Java20Manifest`，不替换失败题。

完整 harness 需要 Linux Docker daemon、SWE-bench CLI/镜像与模型预测文件。建议步骤：

1. 运行 `agentforge doctor`，确保 Docker 可用并构建 `agentforge-sandbox:java21`。
2. 使用 manifest 的 instance_id 生成预测；每题相同 prompt、工具、30 步和 20 分钟预算。
3. DeepSeek 与 Ollama 各跑一次；累计 DeepSeek 估算费用到 ¥50 立即停止。
4. 使用官方 SWE-bench harness 运行测试，不读取 gold patch。
5. 合并 Agent manifest、usage、工具日志与 harness 结果；环境失败单独统计。

```bash
python -m swebench.harness.run_evaluation \
  --dataset_name SWE-bench/SWE-bench_Multilingual \
  --split test \
  --predictions_path predictions.json \
  --max_workers 1 \
  --run_id agentforge-java20
```

当前轻量 `agentforge eval run --suite java20` 只生成固定 manifest；若外部 harness 尚未执行，会为 20 题写入 `ENVIRONMENT_FAILED`，不会伪造 resolved rate。

## 消融

从 Java20 固定前 10 题比较：

- baseline：朴素窗口、原始错误反馈、无仓库索引。
- full：75% 上下文压缩、结构化 Schema 错误、仓库索引。

控制变量：同一 Provider/model id、价格快照、prompt、工具、最大步骤、墙钟时间和测试镜像。每个配置执行一次只是工程样本，不应宣称统计显著性。

## 指标定义

- resolved rate：官方 harness 判定 resolved 的题数 / 可执行题数。
- environment failure rate：环境失败 / 固定总题数。
- tool success rate：成功 ToolResult / 实际执行工具数。
- schema error rate：参数/Schema 错误 / ToolCall 数。
- compression rate：`1 - tokensAfter/tokensBefore`。
- p50/p95：逐工具墙钟耗时分位数，不能用平均值替代尾延迟。
- estimated cost：Provider usage × manifest 价格；与账单误差要单独披露。

## README 数据更新

```bash
java -jar agentforge-cli/target/agentforge-cli-0.1.0-SNAPSHOT.jar eval render-readme \
  --report build/reports/agentforge/micro-full-results.json \
  --readme README.md
```

渲染器只修改 `BENCHMARK:START/END` 区域，防止人工筛选结果。
