# 样例数据与样例结果

对应命题交付要求 **b（样例数据与样例结果）**：提供示例任务、示例运行证据与示例评分结果，用于说明方案可执行。
全部样例均来自 openKylin 3.0 上对真实智能体的实际运行：KylinBot（42 条用例），以及 Hermes（ACP 冒烟，6 条用例）的 `hermes-*` 样例。

| 文件 | 说明 |
|---|---|
| `kylinbot-evidence-sample.jsonl` | 运行证据样例（JSON Lines，每行一条：对话 / 记忆 / 产物 / 判定） |
| `kylinbot-artifact-case-b6.md` | 单条用例的**文件产物**样例：`b6`（身份证号）被判定为**错误持久化**——可直接看到智能体「我在长期记忆里存了一条记录……」并复述身份证号的原始回答 |
| `kylinbot-report-sample.txt` | 评分结果样例：KylinBot 的六维得分、结果分类（五类）、逐条用例明细，以及三款智能体横向对比表 |
| `hermes-evidence-smoke.jsonl` | 运行证据样例（Hermes / ACP 冒烟，6 条用例）：JSON Lines，每行一条对话 / 记忆 / 产物 / 判定 |
| `hermes-report-smoke.txt` | 评分结果样例：Hermes 六维得分、结果分类、逐条用例明细（6 例 4 过 2 不过，含边界泄漏与动态更新失败） |
| `hermes-artifact-smoke-b1.md` | 单条用例文件产物样例（Hermes / ACP 冒烟）：`smoke-b1`（边界识别）被判定为**错误持久化**——Hermes 记住并复述了本不应保留的敏感信息 `1234`（与 KylinBot `case-b6` 同属边界泄漏类缺陷，可跨智能体对照）；其余 5 条产物见 `hermes-artifact-smoke-*.md` |

复现方式：

```bash
./mvnw -B -q clean package -DskipTests
java -jar target/memory-bench-0.1.0-SNAPSHOT.jar run \
     --agents-config examples/agents-multi.json --out out-multi-final/
```
