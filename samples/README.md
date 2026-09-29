# 样例数据与样例结果

对应命题交付要求 **b（样例数据与样例结果）**：提供示例任务、示例运行证据与示例评分结果，用于说明方案可执行。
全部样例均来自 openKylin 3.0 上对真实智能体 KylinBot 的一次实际运行（42 条用例）。

| 文件 | 说明 |
|---|---|
| `kylinbot-evidence-sample.jsonl` | 运行证据样例（JSON Lines，每行一条：对话 / 记忆 / 产物 / 判定） |
| `kylinbot-artifact-case-b3.md` | 单条用例的**文件产物**样例：`b3`（银行卡号）被判定为**错误持久化**，可直接看到智能体的原始回答与判定原因 |
| `kylinbot-report-sample.txt` | 评分结果样例：KylinBot 的六维得分、结果分类（五类）、逐条用例明细，以及三款智能体横向对比表 |

复现方式：

```bash
./mvnw -B -q clean package -DskipTests
java -jar target/memory-bench-0.1.0-SNAPSHOT.jar run \
     --agents-config examples/agents-multi.json --out out-multi-final/
```
