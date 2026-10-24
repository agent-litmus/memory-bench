# SPEC-EVIDENCE · 证据与运行契约协议 v1.0

本文件是 AgentLitmus 的**对外协议**。它规定一次评测运行必须产出什么、每个字段是什么含义、
判定语义如何解读。

**为什么要冻结成协议**：本工具的参考实现是 Java，但「全平台」的完整含义是跨 OS + 跨语言 +
跨智能体框架。任何人用 Python / Go / Rust 写一个 runner，只要产出符合本规范的
`evidence/<agent>.jsonl` 与 `manifest.json`，就能直接用本仓库的评分器、对账器、离线重评与
回归门禁——不需要跑我们的 Java 代码，也不需要相信我们的实现。

配套 JSON Schema：[`schema/evidence.schema.json`](schema/evidence.schema.json)、
[`schema/manifest.schema.json`](schema/manifest.schema.json)。
Schema 是本文件的机器可读形式；两者冲突时以本文件为准，并应提 issue。

---

## 1. 运行目录布局

```
<run-dir>/
├── manifest.json              运行契约（必需，最后写入）
├── report.txt                 人读报告（可选）
├── report.html                自包含 HTML 报告（可选）
├── radar.svg                  六维雷达图（可选）
├── evidence/
│   ├── <agent-id>.jsonl       主臂证据流（必需，每对象一份）
│   └── <agent-id>-control.jsonl  对照臂证据流（可选）
├── artifacts/                 逐条用例产物（可选，Markdown）
├── attribution.json           归因证据化明细（可选，见 §3.4.1）
├── judge-cache.json           评委层判定缓存（可选）
└── oracle/                    神谕对照逐条结论（可选）
```

必需项只有两个：`manifest.json` 与至少一份 `evidence/*.jsonl`。
其余是便利产物，缺失不影响评分与对账。

**只读约定**：`litmus regrade` / `litmus compare` / `litmus rerender --out` 对源运行目录**只读**。
任何工具都不得回写源目录——`manifest.json` 哈希了目录内所有其他文件，回写会让对账全部失配，
等于为了加工而毁掉原始证据。`rerender` 不给 `--out` 时，只要源目录里存在 `manifest.json`
就直接拒绝执行，而不是"就地画完再提醒你 verify 会红"。

---

## 2. 证据流 `evidence/<agent-id>.jsonl`

JSON Lines：一行一条 JSON 对象，UTF-8，行内不得有裸换行。文件名去掉 `.jsonl` 即被测对象 ID。

### 2.1 字段

| 字段 | 类型 | 必需 | 含义 |
|---|---|---|---|
| `type` | string | 是 | `dialogue` / `memory` / `action` / `artifact` |
| `sessionId` | string | 是 | 所属会话；单用例单会话，形如 `case-<caseId>` |
| `timestamp` | integer | 是 | 产生时间，Unix 毫秒，必须 > 0 |
| `content` | string | 是 | 证据内容；`artifact` 类可以是文件路径 |
| `metadata` | object | 是 | 字符串→字符串；无附加信息时给 `{}` |
| `seq` | integer | 是 | 本流内序号，**从 1 开始连续递增，不得断号** |
| `sha256` | string | 是 | `content` 的 sha256 十六进制小写 |

`seq` 与 `sha256` 由收集器盖章，不是 runner 自己随手填的：断号说明这份流被裁剪过，
哈希不匹配说明内容被改过，两者都能离线查出来（见 §5）。

**这不是签名**。能改文件的人也能重算哈希。它挡的是「悄悄改一行」与「传输损坏」，
不挡恶意伪造；整份运行的对账由 `manifest.json` 负责，两层各管一段。

### 2.2 `type=dialogue` 与 `metadata.role`

`role` **必填**。一个会话里提问与回答都是 `dialogue`，只靠先后顺序推断「哪条是回答」是脆的：
离线重评要在没有代码上下文的情况下把回答捞出来重判，顺序一旦错位，它会拿提问去判分，
而且**不会报错**，只会给出假数字。

| `role` | 含义 |
|---|---|
| `seed` | 注入的事实（用户先把信息告诉智能体） |
| `update` | 更新事实（告知新值，期望覆盖旧值） |
| `question` | 评测方的提问 |
| `answer` | **被测对象的回答——离线重评只认这一种** |
| `note` | 评测框架自己写的说明（会话重置、能力门控等），不是任何一方的发言 |

### 2.3 `type=artifact` 与 `metadata.kind`

| `kind` | 含义 | 附加字段 |
|---|---|---|
| `judgment` | 本条用例的裁决记录 | `verdict`：`PASS` / `FAIL` / `UNKNOWN`（**必填**） |
| `transcript` | 逐条用例的完整过程产物，`content` 是文件路径 | — |

裁决必须同时给出结构化 `metadata.verdict` 与人读 `content`。只留文本会让读回依赖
`Judgment.toString()` 的字段顺序——给判定记录加一个字段就可能悄悄改变读回结果，
这类故障不报错，只会让门禁基于错误的基线放行或拦截。**结构化字段是协议，文本是给人看的。**

### 2.4 `type=memory` / `type=action`

- `memory`：记忆状态证据。`metadata.entryId` 可选，指向被测对象自己的记忆条目 ID。
  仅在能力档位达到 T2（记忆可观测）时产出。
- `action`：行动证据。`metadata.tool` 记录来源。**只收评测方自己观测到的进程级事实**
  （命令行、退出码、耗时）。被测对象自报的「我调用了某工具」不进这一层——
  自报无法核对，混进来等于把证据变成声明。

### 2.5 每条用例的最小完整证据序列

```
dialogue role=seed      ×N   （注入事实）
dialogue role=update    ×0-1 （动态更新）
dialogue role=note      ×0-1 （跨会话用例的上下文重置说明）
dialogue role=question  ×1
dialogue role=answer    ×1
artifact kind=judgment  ×1   （含 metadata.verdict）
artifact kind=transcript ×0-1
```

能力门控跳过的用例（前提不成立、判为不可判）**同样必须产出 `judgment` 记录**。
否则离线重评与跨运行比较会把这道题看成「压根没这道题」，于是覆盖率下降变得不可见。

---

## 3. 判定语义

### 3.1 三值裁决

| 裁决 | 含义 | 是否进入分母 |
|---|---|---|
| `PASS` | 通过 | 是（分子 + 分母） |
| `FAIL` | 未过 | 是（仅分母） |
| `UNKNOWN` | 本次未能判定 | **否** |

`UNKNOWN` 的存在理由：只有两态时，「超时、限流、空回答、缺评委模型」这些**评测导管故障**
会被塞进「未过」，于是通过率同时混合了「被测对象记不住」和「这次没测成」两种完全不同的事实。
不可判单独公示，**不静默丢弃、也不算失败**。

### 3.2 三个口径（必须同时给出，缺一不可）

| 口径 | 公式 | 回答的问题 |
|---|---|---|
| 有效分 `effectiveRate` | PASS / 已判定 | 在能判的题上，它做对了多少 |
| 覆盖率 `coverage` | 已判定 / 计划 | 我们究竟测成了多少 |
| 悲观下界 `lowerBound` | PASS / 计划 | 就算不可判全算失败，也至少有这么多 |

只报有效分是虚高（分母被移动过却不说），只报下界是虚低（把评测故障算成能力缺陷）。
三个一起给，读者才能自己判断该信哪个。

### 3.3 判定口径版本 `rulesVersion`

当前：`2026.10-c`。

口径会变（归一化规则、弃权规则、归因分类），而真实智能体一轮评测要几十分钟和真金白银的额度。
因此版本号是**载荷**而不是装饰：

- `manifest.json` 必须记录产出这批数字的 `rulesVersion`；
- 两次运行 `rulesVersion` 不同时，**禁止**直接比较——差值同时包含「尺子变了」与「东西变了」，
  任何回归结论都不成立。正确做法是先对较早的一侧执行 `litmus regrade` 把口径对齐；
- `litmus regrade` 用当前口径重判旧证据，逐条列出裁决变化。因为不重跑被测对象，
  差值**只能**来自口径本身，这才是「换了尺子」与「东西变了」的分界线。

历史口径：`2026.10-a`（两值）、`2026.10-b`（三值）、`2026.10-c`（关键词归一化 +
边界严格/排除项歧义分流 + 非回答检测）。

### 3.4 归因 `FailureCause`

失败不只分类，还要回答「发生在记忆生命周期的哪一阶段、由哪类可观测信号暴露、应如何改进」。
取值：`NO_RESPONSE`、`PERSIST_MISSING`、`RECALL_MISSING`、`SIMILAR_CONFUSED`、
`BOUNDARY_LEAKED`、`STALE_REUSE`、`BASE_OR_EXEC`、`NOT_OBSERVABLE`、`NEEDS_SEMANTIC_JUDGE`。

其中两个是**移出分母**而非计为失败：

- `BASE_OR_EXEC`：由神谕对照**观测**得出（提问时已附上正确值仍答错），失败不在记忆环节。
  留在分母里，「模型本身不行」会被记成「记忆不行」，改进方向完全跑偏。
- `NOT_OBSERVABLE`：用例前提在该平台不成立（如跨会话题要求能清空上下文只留长期记忆，
  而平台复位不了）。这不是被测对象的缺陷，是**评测的边界**；记成失败等于给平台扣一顶
  「我们测不了」的帽子。

#### 3.4.1 归因证据化比例

归因读起来都像结论，但证据强度完全不同：记忆可读时「旧值仍在库里」是**观测事实**，
记忆在被测对象内部读不到时同一句归因只是**从回答文本做的推断**。混在一个"归因分布"里报出去，
读者无从判断该信到什么程度。因此定义：

```
归因证据化比例 = 由独立证据支撑的归因条数 / 全部带归因的判定条数      目标 ≥ 0.80
```

**独立证据**指回答文本之外的证据；每类归因各自要求一种，判据如下：

| 归因 | 需要的独立证据 |
|---|---|
| `STALE_REUSE` / `BOUNDARY_LEAKED` / `SIMILAR_CONFUSED` | 该会话记忆快照里**确实存在**那个不该出现的值（`expectedNotContains`） |
| `RECALL_MISSING` | 记忆快照里**确实存在**该记住的值（`expectedContains`）——存了没召回，而非没存 |
| `PERSIST_MISSING` | 该会话有记忆快照且**不含**该记住的值 |
| `NO_RESPONSE` | 该会话有 `type=action` 证据（命令行 / 退出码 / 是否超时） |
| `BASE_OR_EXEC` | 该用例有神谕对照记录 |
| `NOT_OBSERVABLE` | 能力声明里**确实缺**该用例要求的能力；既称可复位又判观测不足属配置矛盾，记未证据化 |
| `NEEDS_SEMANTIC_JUDGE` | 用例约束形态本身构成冲突：只约束排除项，或期望项与排除项在快照里并存 |

记忆快照是否可得由模式与能力决定：`RunnerMode.MEMORY`（记忆由框架写入、逐条可读）本身即等于可读；
`RunnerMode.DIALOGUE` 只有在声明了 `MEMORY_INSPECTABLE`（例如配置给了 `stateDir`）时才算可读。
**不可得时，依赖快照的归因一律记为未证据化并逐条列出缺什么证据**——
这类归因此时就是推断，把它们算成"有证据"正是本口径要防的事。

`attribution.json` 结构：`{ target, agents: [{ agent, attributed, backed, ratio, meetsTarget,
basis, byCause: { <CAUSE>: { backed, attributed } }, unbacked: [...] }], minRatio, meetsTarget }`。
`minRatio` 取全体被测对象的最低值：**只要有一款以推断为主，就不能笼统说「归因有据」**。

该文件必须在 `manifest.json` 之前写完，从而被计入产物哈希；否则「归因有多少条没证据」
这句话本身就没有对账依据。`litmus regrade` 不会重算它——重评后引用该比例需按新口径重跑 `run`。

### 3.5 能力档位 `ConfidenceTier`

| 档位 | 含义 | 能观测到什么 |
|---|---|---|
| `T1` | 仅对话 | 只有回答文本 |
| `T2` | 记忆可观测 | 回答 + 记忆落盘目录的变化 |
| `T3` | 行动可观测 | 回答 + 记忆 + 进程级行动轨迹 |

档位由 `litmus probe` 探测或配置声明，**必须同时公示「该档缺什么、为什么缺、怎么补」**，
且内置被测对象受同样约束。否则分档会被读成「给自己台阶下」。

能力枚举：`RESETS_CONTEXT`、`MEMORY_INSPECTABLE`、`TOOL_CALLS_VISIBLE`、
`ARTIFACTS_VISIBLE`、`STATE_RESETTABLE`。

### 3.6 维度与结果分类

维度 `Dimension`：`RETENTION`（长期保持）、`RECALL`（记忆调用）、`UPDATE`（动态更新）、
`DISCRIMINATION`（相近区分）、`BOUNDARY`（边界识别）、`REUSE`（任务复用）。

结果 `Outcome` 五类：`CORRECT`、`OMISSION`、`CONFUSION`、`WRONG_PERSISTENCE`、`WRONG_REUSE`。
仅在 `PASS`/`FAIL` 上有意义，`UNKNOWN` 不带结果类别。

---

## 4. 运行契约 `manifest.json`

| 字段 | 类型 | 含义 |
|---|---|---|
| `schemaVersion` | string | 本协议版本，当前 `1.0` |
| `generatedAt` | string | ISO-8601 生成时间 |
| `litmusVersion` | string | 产出该运行的工具版本 |
| `gitCommit` | string | 工具代码的 commit；取不到时为空串 |
| `rulesVersion` | string | 判定口径版本（见 §3.3） |
| `caseSetSha256` | string | 题集规范化 JSON 的 sha256 |
| `caseCount` | integer | 计划题数（分母的「计划」） |
| `suite` | string\|null | 题集档位；外置题集为 `null` |
| `environment` | object | `osName`/`osVersion`/`osArch`/`javaVersion`/`javaVendor` |
| `params` | object | `repeat`、`parallel` |
| `agents` | array | 逐对象：`id`/`name`/`mode`/`role`/`adapter`/`description`/`capabilities`/`confidenceTier`/`isolation` |
| `scores` | object | 对象名 → `effectiveRate`/`coverage`/`lowerBound`/`total`/`judged`/`unknown` |
| `files` | object | 相对路径 → sha256，覆盖运行目录内**除 manifest 自身以外**的所有常规文件 |

**写入时机**：必须在所有其他产物落盘**之后**写，否则哈希不到它们。`judge-cache.json`
也是产物，同样要先落盘再写契约。

`isolation` 字段不得留空。HTTP 接入不启动被测进程时，要写清「隔离责任在被测服务自身」——
留空会被读成「已隔离」。

---

## 5. 对账与自检

### 5.1 `litmus verify --from <run-dir>`

重算契约里每一项哈希并与目录比对，同时用**契约记录的档位**重算题集哈希。任何一处不一致都指出
是哪一处，并以非零退出码结束。检查项：产物被改动、产物缺失、目录里混入未登记文件、题集不一致。

用默认档位去对一个 `full` 档的目录只会得到假的「题集不一致」——假的失败比没有检查更糟，
它会让人以为对账这个动作本身不可信。

### 5.2 证据流自检

对 `evidence/*.jsonl` 逐条检查：字段完整、`type` 合法、`seq` 从 1 连续、`sha256` 与 `content` 一致。

**只认 `evidence/` 目录下的 `.jsonl`**。运行目录里还有别的 `.jsonl`
（例如内置被测自己的记忆落盘），它们不是证据流，没有序号与内容哈希，
拿证据契约去查只会产出一堆假问题。

### 5.3 `litmus selftest`

用随 jar 发布的标注集量**判定器自己**，报一致率 / Cohen's κ / 弃权率 / 逐条分歧。
不接触任何被测对象，因此可进 CI。门禁只卡**能判的**项：规则层开口即对率必须达标；
评委层 κ 只有在评委真的启用时才算，没启用就写「不可判」而不是算它通过——
用缺席的评委去证明尺子准，是这套工具在别处一律拒绝的动作。

κ 必须与一致率一起报：两者会在同一批数据上给出不同结论，而只看一致率会高估尺子。

---

## 6. 跨运行比较 `litmus compare <A> <B>`

从两侧证据流里**照抄**各自当时的裁决（不重判），只在两侧都已判定的同一批题上做配对，
bootstrap 95% 区间（B=1000，固定种子，同输入必然同输出，第三方可逐字节复现）。

红线（非零退出码）：

1. 两侧 `rulesVersion` 不同；
2. 两侧 `caseSetSha256` 不同；
3. 任一侧缺 `manifest.json`；
4. 配对差值显著为负（95%CI 不含 0）；
5. 新增不可判 ≥ 1（已判定的题变少意味着分数口径被移动过）。

**逐题回归本身不是红线**。真实智能体有抖动，单题翻面是常态；这类情况照实列进明细与提示，
但不阻断。门禁太紧的后果是所有人开始加 `|| true`，那比没有门禁更糟。

区间跨 0 时结论写「未见显著差异」，**不写成平手**，也不许把小数点后的差值当优势。

重复评测（`--repeat N`）时同一用例有多条裁决，比较只用**第一次**——
逐次是否翻面是稳定性统计的职责，不是比较的职责。

---

## 7. 实现自由与兼容

- **允许**：任何语言、任何智能体框架、任何 OS，只要产出符合 §2/§4 的文件。
- **允许**：不产出可选产物（`report.html`、`radar.svg`、`artifacts/`）。
- **不允许**：省略 `metadata.role` 或 `metadata.verdict`。缺 `role` 的证据流会被
  `litmus regrade` **如实拒绝**（报「旧格式，不重评，也不猜」），而不是靠顺序推断。
- **不允许**：把 `UNKNOWN` 折算成 `FAIL` 后写入。这会同时污染有效分与覆盖率。
- **不允许**：把被测对象自报的工具调用写成 `type=action`。

向后兼容：读回裁决时，若 `metadata.verdict` 缺失，会退回解析 `content` 里的
`verdict=<X>`，再退回两值时代的 `passed=<true|false>`。这是**兼容路径**而非常规路径，
新 runner 不应依赖它。
