# AgentLitmus · Memory Bench

> **AgentLitmus（智能体试金石）** — 智能体长期记忆评测基准
> **区分「跑通了」与「能落地」**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17%2B-orange.svg)](https://www.openjdk.org/)
[![Platform](https://img.shields.io/badge/openKylin-3.0-brightgreen.svg)](https://www.openkylin.top/)
[![GitHub](https://img.shields.io/badge/GitHub-agent--litmus%2Fmemory--bench-181717?logo=github)](https://github.com/agent-litmus/memory-bench)
[![Gitee](https://img.shields.io/badge/Gitee-agent--litmus%2Fmemory--bench-C71D23?logo=gitee)](https://gitee.com/agent-litmus/memory-bench)

**开源仓库**

| 平台 | 地址 | 说明 |
|---|---|---|
| GitHub | <https://github.com/agent-litmus/memory-bench> | 主仓（组织 `agent-litmus`） |
| Gitee | <https://gitee.com/agent-litmus/memory-bench> | 国内镜像，与 GitHub 同步 |

品牌 **AgentLitmus（智能体试金石）** ｜ 组织 `agent-litmus` ｜ 包名 `io.github.agentlitmus` ｜ 协议 Apache 2.0

> ### 实测与核验（可自行复现）
>
> | 项 | 值 |
> |---|---|
> | 实测环境 | openKylin 3.0（huanghe）x86_64 ｜ OpenJDK 17.0.11 ｜ KylinBot（`/usr/bin/kylin-bot`） |
> | 数据集 | 内置 42 条（六维，含 7 条跨会话用例） |
> | 实测对象 | 参考智能体 baseline ／ **KylinBot（真实智能体）** ／ 缺陷智能体 degraded |
> | 实测结果 | **100% ／ 64% ／ 52%** —— 尺子具备分辨力（详见 [接入真实智能体](#接入真实智能体openkylin-kylinbot-实战)） |
> | 运行证据 | 每款智能体 227~234 条 JSONL 证据，空回答 0 条；每条用例另有 Markdown 产物 |
> | 样例结果 | [`samples/`](./samples) —— 可直接查看的证据、产物与评分样例 |
> | 一键复现 | `./scripts/verify-openkylin.sh`（环境采集 + 全量测试 + 评测） |

📄 **[方案说明文档（SOLUTION.md）](./SOLUTION.md)** — 测试目标、前提、数据生成、用例设计、证据收集与自动评分完整流程

面向 openKylin 生态设计的一套**自动化长期记忆评测 benchmark**：把对话、记忆记录、行动轨迹与运行产物统一组织为证据，基于证据自动评估智能体的长期记忆能力，产出多维指标。

**核心库零框架依赖**（仅 Jackson + slf4j），可直接打包为 CLI 与 `.deb` 分发。

## 为什么做这个

智能体进入办公、研发、个人助理等场景后，"能否长期、稳定、可控地记住用户信息"成为可用性的关键。但当前评测存在三个明显不足：

1. **依赖人工检查**，难以规模化；
2. **简单问答式验证**无法覆盖真实使用中的冲突更新、相似干扰与行动复用；
3. **自动评分容易语义偏移**，难以稳定判断模型到底是"记住了""误记了"还是"不该记却记了"。

本项目的应对思路是：**基于运行证据而非人工问答，规则优先而非模型优先**。

## 六个评测维度

| 维度 | 评测内容 | 内置用例 |
|------|----------|---------|
| 长期保持 | 应保留的信息是否被稳定记住并在后续使用（含跨会话） | `r1`–`r9`（9 条） |
| 记忆调用 | 后续交互中是否能正确调用相关信息 | `c1`–`c6`（6 条） |
| 动态更新 | 新信息出现后是否能正确覆盖或修正旧信息（含跨会话） | `u1`–`u7`（7 条） |
| 相近区分 | 是否能识别相近信息之间的差异 | `d1`–`d6`（6 条） |
| 边界识别 | 不应长期保留或不应复用的信息是否被正确识别（含跨会话） | `b1`–`b8`（8 条） |
| 任务复用 | 实际任务执行中是否能合理使用历史信息 | `t1`–`t6`（6 条） |

其中 `r5` `r6` `r8` `u5` `u7` `b5` `b8` 共 7 条为**跨会话用例**：注入事实后先重置会话上下文（仅保留长期记忆），
再提问——答对只能来自长期记忆，不能靠上下文窗口，这才是「长期记忆」的本义。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+（或直接用项目自带的 `./mvnw`）
- **不需要 API Key**：内置用例全部走规则判定，离线可跑完整套

### 运行评测

```bash
./mvnw test -Dtest=MemoryBenchmarkRunnerTest
```

输出示例：

```
========== 长期记忆评测报告 ==========
用例总数: 42    通过: 42    通过率: 100.0%
-------------------------------------
长期保持      9/9    100.0%
记忆调用      6/6    100.0%
动态更新      7/7    100.0%
相近区分      6/6    100.0%
边界识别      8/8    100.0%
任务复用      6/6    100.0%
-------------------------------------
明细:
  [通过] u1     动态更新   包含[客户关系管理专员] 命中；排除[订单录入员] 未出现(合格)
  [通过] b1     边界识别   排除[1234] 未出现(合格)
  ...
=====================================
```

### 使用 CLI（推荐：批量对比 + 雷达图）

打包后即可一键运行，支持导入不同智能体配置进行**批量对比评测**：

```bash
./mvnw -q clean package -DskipTests
java -jar target/memory-bench-0.1.0-SNAPSHOT.jar run --out result/
```

常用命令：

```bash
litmus run                                   # 内置两款智能体对比 + 内置用例
litmus run --agents reference,degraded       # 指定被测智能体
litmus run --agents-config examples/agents-kylinbot.json --out out/   # 导入外部智能体配置
litmus run --cases my-cases.json --out out/  # 使用外部数据集
litmus export-cases --out out/               # 导出内置用例，便于扩展数据集
```

产物：

| 文件 | 说明 |
|---|---|
| `report.txt` | 各智能体报告 + 横向对比表（纯文本，可直接贴进申报材料） |
| `report.html` | **含六维雷达图**的自包含报告，浏览器打开即见（便于录屏演示） |
| `radar.svg` | 雷达图单独文件 |
| `evidence/<agent>.jsonl` | 每个智能体的运行证据（JSON Lines，命题要求"证据统一组织"） |

内置两款被测智能体（均无需 API Key，结果完全可复现）：

| id | 说明 | 典型表现 |
|---|---|---|
| `reference` | 参考智能体（baseline）：基于相关性召回，记忆能力健全 | 六维 100% |
| `degraded` | 缺陷智能体：不按相关性检索，总是取最早写入的记忆 | 总体 52%，动态更新 0% |

实测对比（内置 42 条用例，含 7 条跨会话用例）：

```
智能体                   长期保持  记忆调用  动态更新  相近区分  边界识别  任务复用  总体
参考智能体（baseline）      100%     100%     100%     100%     100%     100%    100%
缺陷智能体（degraded）      100%     100%       0%      33%       0%      83%     52%
```

这个对比的意义在于：**评测能稳定区分不同智能体的记忆能力差异**——若两款表现都满分，尺子就失去了分辨力。

### 打包 openKylin / Debian 安装包

```bash
# 注意：必须在 openKylin / Linux 上执行（jpackage 不支持交叉编译，macOS 打不出 deb）
./scripts/build-deb.sh     # 产出 dist/agent-litmus_0.1.0_*.deb
sudo dpkg -i dist/agent-litmus_0.1.0_*.deb
agent-litmus run --out ~/litmus-result
```

> **`.deb` 自带运行时，无需系统预装 Java。** `jpackage` 会把精简 JRE 打进安装包（bundled runtime），
> 安装后可直接执行 `agent-litmus`，不需要 `apt install openjdk`。
> 只有在直接用 `java -jar` 跑 fat jar 时才需要 JRE 17+。

### 五类结果分类

评分不只给"通过/未过"，而是区分**错在哪一种记忆能力**上（命题要求）：

| 类别 | 含义 |
|---|---|
| 正确记忆 | 该记住的记住了，不该用的没用 |
| 遗漏 | 该用的信息没出现在回答中 |
| 混淆 | 混入相近干扰信息 |
| 错误持久化 | 不该保留的信息被记住并复用 |
| 错误复用 | 更新后仍用旧值 |

缺陷智能体实测分类：`正确记忆 13 / 遗漏 1 / 混淆 2 / 错误持久化 4 / 错误复用 4`。

### 在 openKylin 上一键验证

```bash
./scripts/verify-openkylin.sh
```

脚本会采集环境信息（系统 / JDK / 内存 / 架构）+ 跑全量测试 + 跑评测并输出多维指标。

### 接入真实智能体：openKylin KylinBot 实战

内置两款用于证明「尺子有效」，真正有说服力的评测必须落在真实智能体上。
已在 **openKylin 3.0（x86_64 / JDK 17.0.11）** 完成 KylinBot（麒灵助手）接入实测。

**选型：为什么用 CLI 而不是 Gateway / ACP**

| KylinBot 接法 | 能否直接接入 | 原因 |
|---|---|---|
| CLI（`kylin-bot agent -m`） | ✅ `command` 类型直接接 | 每轮独立进程，配合会话状态文件即可保持多轮 |
| Gateway HTTP（Unix Socket） | ❌ | 需 Bearer Token、字段是 `message`、响应非 SSE；`HttpAnswerer` 不支持 Unix Socket 与自定义请求头 |
| ACP（JSON-RPC over stdio） | ❌ 需自写常驻中继 | 每轮新起进程，无法复用 stdio 会话 |
| Telegram 等外部通道 | ❌ | 异步，无法同步取回答 |

**适配器**（`scripts/kylinbot-adapter.sh`）解决两个硬约束：

1. **用例间记忆污染** —— 每个会话用独立 `--config-dir`（KylinBot 记忆库 `workspace/memory/brain.db` 随配置目录走），
   42 条用例互不干扰，且**不触碰用户真实的 `~/.kylinbot` 记忆**；
2. **多轮上下文** —— `--session-state-file` 落盘会话状态，保证多次独立调用仍是同一段会话。

> 注意：`--config-dir` 必须**同时**拷贝 `config.toml` 与 `.secret_key`，否则 `api_key` 的 enc2 解密会失败。

**配置**（`examples/agents-kylinbot.json`）：

```json
{
  "id": "kylinbot", "name": "KylinBot（麒灵助手）", "type": "command",
  "command": "bash",
  "args": ["scripts/kylinbot-adapter.sh", "{sessionId}", "{question}"],
  "timeoutSeconds": 180
}
```

**运行**：

```bash
java -jar target/memory-bench-0.1.0-SNAPSHOT.jar run \
     --agents-config examples/agents-multi.json --out out-multi-final/   # 三款智能体一次对比
```

**实测结果**（42 条用例，六维，三款同跑一次产出）：

| 智能体 | 长期保持 | 记忆调用 | 动态更新 | 相近区分 | 边界识别 | 任务复用 | 总体 |
|---|---|---|---|---|---|---|---|
| 参考智能体（baseline） | 100% | 100% | 100% | 100% | 100% | 100% | 100% |
| **KylinBot（麒灵助手）** | 89% | 83% | 43% | 50% | 50% | 67% | **64%** |
| 缺陷智能体（degraded） | 100% | 100% | 0% | 33% | 0% | 83% | 52% |

结果分类（KylinBot）：`正确记忆 27 / 遗漏 6 / 混淆 2 / 错误持久化 4 / 错误复用 3`。

**跨会话长期保持**（`r5` `r6` `r8` `u5` `u7` `b5` `b8`，注入后重置对话上下文再提问）：
KylinBot 长期保持维度仍达 89%，说明其表现主要来自长期记忆，而非上下文窗口。

**一个真实发现**：`b3`（银行卡号 6222021234567890）被判定为**错误持久化**——
KylinBot 原样复述了该卡号（见 `samples/kylinbot-artifact-case-b3.md`）。
这说明评测不只是打分，而是能定位到具体的记忆治理缺陷。

> ⚠️ **关于复现波动**：被测对象调用云端模型，同一数据集多次运行会波动（本数据集实测 64%~69%，
> 早期 28 条数据集实测 86%）。这不代表工具不稳定——内置 `reference` / `degraded` 两次运行结果完全一致，
> 波动来自被测智能体自身。因此提交材料时应**同时给出对照组**，以证明「尺子本身」稳定。

**校验是否真接上**（比看分数更可靠）：

```bash
# 证据行数 + 空回答数（空回答=没连上却被判 0 分）
python3 -c "
import json;rows=[json.loads(l) for l in open('out-multi-final/evidence/kylinbot.jsonl',encoding='utf-8')]
print('证据条数:',len(rows),'空回答:',sum(1 for r in rows if not r['content'].strip()))"

# 查看某条用例在 KylinBot 记忆库里到底存了什么
kylin-bot --config-dir /tmp/litmus-kylinbot/case-u4 memory list
```

实测 `evidence/kylinbot.jsonl` 共 234 条、**空回答 0 条**；其中 7 条跨会话用例的证据里带有
「重置会话上下文」标记，可核对重置确实执行过。

此外，每条用例都会落盘一份 **Markdown 运行产物**（`artifacts/<agent>/<case>.md`）：
注入了什么、更新了什么、问了什么、答了什么、怎么判的，一文件看完——
对应命题「将对话、记忆记录、行动轨迹、文件或其他运行产物统一组织为证据」。
`u4`（动态更新失败）的根因可由记忆库直接证实：写入了 `user_city=深圳` 与 `user_current_city=杭州`
**两个 key 而非覆盖**，导致回答时自述「两条记录冲突，无法确定」。

> 说明：接入真实智能体后评测**不再离线可复现**——被测对象会调用云端模型，存在随机性与额度消耗。
> 因此提交材料时应同时给出 `reference` / `degraded` 对照组，证明「尺子本身」稳定。

### 参赛录屏方案（3–5 分钟）

| 时长 | 画面内容 | 操作 |
|---|---|---|
| 0:00–0:30 | 环境与项目定位 | `./scripts/verify-openkylin.sh` 的环境采集输出（openKylin 3.0 / JDK 17 / 架构） |
| 0:30–1:10 | 一键验证跑通 | 执行 `./scripts/verify-openkylin.sh`，展示全量测试通过 + 六维报告 |
| 1:10–1:50 | 内置两款对照（尺子有分辨力） | `java -jar target/*.jar run --agents reference,degraded --out out-final-builtin/`，展示 100% vs 52% 对比表与雷达图 |
| 1:50–3:00 | **真实智能体 KylinBot 接入** | 展示 `examples/agents-kylinbot.json` + `scripts/kylinbot-adapter.sh`，执行评测命令 |
| 3:00–3:50 | 结果可视化 | 浏览器打开 `out-multi-final/report.html`（三款智能体六维雷达图）+ 展示 `evidence/kylinbot.jsonl` 真实对话证据 + `artifacts/kylinbot/case-b3.md` 文件产物 |
| 3:50–4:30 | 证据闭环 | `kylin-bot --config-dir /tmp/litmus-kylinbot/case-u4 memory list`，展示失败用例的机制性原因 |
| 4:30–5:00 | 分发形态 | 展示 `dist/*.deb` 安装与 `agent-litmus run`（可选，若已打包） |

录制建议：全程 KylinBot 评测约 10–15 分钟，**不要实时录**——先跑完再录「回放 + 产物讲解」，
或把长耗时片段剪成「命令执行 → 结果」两段拼接。

## 设计要点

### 1. 规则优先、LLM 兜底

能用确定性规则判定的（关键词包含 / 排除），**绝不用模型**——判定本身不应引入新的不确定性，否则"评测结果不稳定"会掩盖被测对象的真实问题。

只有无法用关键词表达的标准才走 LLM；**无模型时降级为规则判定并标注**（`rule-degraded`），不会因缺 Key 就跑不出结果。

### 2. 可信度治理延伸到记忆层

记忆条目带两个属性：

- `credibility`——信息**该不该被信任**
- `retainable`——信息**该不该被留下**

于是"不该留的信息"不是靠模型自觉忘记，而是**存储层就把它排除在召回之外**。这与知识库的可信度分级是同一治理哲学的两个切面：数据层"该不该信"，记忆层"该不该留"。

### 3. 确定性回答器 = 内置参考智能体

`RecallAnswerer` 不调用任何模型，召回什么就说什么。它带来三点好处：无需 API Key、结果完全可复现、衡量的是记忆系统本身的能力。

对标评测时它充当**"记忆能力健全"的对照组（baseline）**，与真实智能体形成对比。

### 4. 接入任意智能体只需一行

核心库不绑定任何框架。接入新被测对象 = 实现 `Answerer`：

```java
Answerer myAgent = (sessionId, question) -> myAgentClient.chat(sessionId, question);

BenchmarkReport report = new MemoryBenchmarkRunner(
        new FileLongTermMemory(Path.of("/tmp/mem")),
        myAgent,
        new CaseJudge()                     // 规则判定
).run(MemoryCases.defaultCases());
```

需要 LLM 判定时，实现 `LlmClient`（单方法）即可，例如接入 Spring AI：

```java
LlmClient llm = prompt -> chatClient.prompt().user(prompt).call().content();
CaseJudge judge = new CaseJudge(llm);
```

### 5. 证据统一组织

`Evidence` 把对话、记忆、行动轨迹、产物收敛成同一种结构，`EvidenceCollector` 收集并导出为 JSON Lines——**一行一条、人可读**，跑完即可直接提交检查。

## 项目结构

按职责分包，入口 `Cli` 保留在根包：

```
io/github/agentlitmus/
├── Cli.java              命令行入口
├── core/                 评测引擎
│   ├── Dimension.java        六个评测维度
│   ├── MemoryCase.java       评测用例模型（可扩展数据结构）
│   ├── Judgment.java         判定结果
│   ├── Outcome.java          五类结果（正确/遗漏/混淆/错误持久化/错误复用）
│   ├── CaseJudge.java        判定器（规则优先、LLM 兜底）
│   ├── MemoryBenchmarkRunner.java   单智能体执行器
│   └── MultiAgentBenchmark.java     多智能体批量对比
├── memory/               长期记忆载体
│   ├── MemoryEntry.java        记忆条目（可信度 + 保留策略）
│   ├── LongTermMemory.java     记忆接口
│   ├── FileLongTermMemory.java 文件持久化（JSON Lines）
│   └── MemoryRegistry.java     智能体与记忆的隔离绑定
├── agent/                被测智能体
│   ├── Answerer.java          被测对象契约（单方法）
│   ├── AgentUnderTest.java    被测智能体身份
│   ├── Agents.java            内置注册表（reference / degraded）
│   ├── RecallAnswerer.java    参考智能体（baseline）
│   └── DegradedAnswerer.java  缺陷智能体（问题样本）
├── evidence/             证据
│   ├── Evidence.java          统一证据结构（对话/记忆/轨迹/产物）
│   └── EvidenceCollector.java 收集与导出
├── report/               报告与可视化
│   ├── BenchmarkReport.java   多维指标报告
│   ├── BenchmarkResult.java   批量对比结果
│   ├── CompareTable.java      横向对比表
│   ├── RadarChart.java        六维雷达图（纯 SVG）
│   └── HtmlReport.java        HTML 报告（自包含）
├── dataset/              数据集
│   └── MemoryCases.java       内置 42 条用例（含 7 条跨会话）+ JSON 加载/导出
└── llm/                  外部模型接入
    └── LlmClient.java        单方法抽象，零框架绑定
```

## 踩坑记录

### Jackson 会把 record 的 isXxx() 当成属性序列化

`MemoryEntry` 中的 `isActive()` 被 Jackson 当作 boolean getter 写出 `active` 字段，但反序列化走 record 规范构造器时没有该参数，导致**写入成功却读不出来**（静默丢数据，编译期不报错）。

处理：`@JsonIgnore` + ObjectMapper 忽略未知字段。**教训：record 里不要随手写 `isXxx()` 这类符合 getter 命名的方法。**

### 中文检索不能只按空格切词

查询"我上周去了杭州"匹配不到含"杭州"的记忆——中文没有空格，整句被当成一个词。

处理：对含汉字的片段生成二字滑窗（bigram）；纯英文 / 数字片段仍按整词，避免"ab"命中"abc"这类误匹配。

## 文档

| 文档 | 内容 |
|---|---|
| [SOLUTION.md](./SOLUTION.md) | **方案说明**：测试目标、基本前提、数据生成、用例设计、证据收集、自动评分流程（命题交付 a） |
| [README.md](./README.md) | 使用说明：快速开始、CLI、打包、设计要点 |

## License

[Apache License 2.0](LICENSE)

---

**AgentLitmus** — 智能体试金石 ｜ 区分「跑通了」与「能落地」
