# 使用说明

四件事：**不用 Key 试用** → **接入新智能体** → **读报告** → **改完复验**。
协议细节（字段、判定语义、跨语言 runner）见 [`SPEC-EVIDENCE.md`](SPEC-EVIDENCE.md)。

命令名：源码里写作 `litmus`，`.deb` 安装后是 `agent-litmus`；用 jar 则是
`java -jar target/memory-bench-<version>.jar`。下文统一写 `litmus`。

---

## 0. 前置

- JDK **17 或更高**（开发机实测 JDK 21）。运行期依赖已全部打进 fat jar，**不需要联网**。
- 构建：`mvn -o package`（离线可构建；依赖只有 Jackson + slf4j + JUnit）。
- 评委层（可选）需要一个 OpenAI 兼容端点的密钥，**只从环境变量读**，见 §4。

```bash
mvn -o package                      # → target/memory-bench-<version>.jar
sudo bash scripts/build-deb.sh      # → dist/agent-litmus_<version>_<arch>.deb（openKylin/Debian 系）
```

---

## 1. 不用 Key 试用（30 秒）

```bash
litmus run --suite basic --out out/trial
```

这条命令跑的是**内置的两款被测对象**（`reference` 健全实现 / `degraded` 缺陷样本），
150 道题，全程离线、确定性、不调用任何模型。它的用途不是「评测某个产品」，
而是让你先看清这套工具**产出什么形状的证据与报告**，以及缺陷样本是否真的被抓出来。

产物：

```
out/trial/
├── manifest.json    运行契约：题集哈希、口径版本、环境、每个产物的 sha256
├── report.txt       人读报告（可直接贴进材料）
├── report.html      自包含 HTML（浏览器打开即见，便于演示与分享）
├── radar.svg        六维雷达图
├── artifacts/       逐条用例的完整过程（Markdown，可点开看单题）
├── attribution.json 归因证据化明细（哪些归因有独立证据、哪些只是推断）
└── evidence/        每款被测一份 JSON Lines 证据流
```

想快就 `--suite basic`（150 题）；想看全貌用 `--suite full`（283 题）。档位见 §5。

---

## 2. 读报告

### 2.1 三个口径，必须一起看

| 口径 | 公式 | 只报它会怎样 |
|---|---|---|
| **有效分** | 通过 / 已判定 | 虚高——分母被移动过却不告诉你移动了多少 |
| **覆盖率** | 已判定 / 计划 | 单独看没有意义，它是有效分的可信度 |
| **悲观下界** | 通过 / 计划 | 虚低——把评测故障算成能力缺陷 |

`UNKNOWN`（不可判）**不进分母也不算失败**。它来自两类事实：评测导管故障
（超时/限流/空回答）、用例前提在该平台不成立（如跨会话题要求能清空上下文，而平台复位不了）。
报告里逐条公示原因，`artifacts/` 里能点开看原始证据。

### 2.2 报告里其余几块各回答什么

- **六维雷达**：长期保持 / 记忆调用 / 动态更新 / 相近区分 / 边界识别 / 任务复用。
- **归因与改进建议**：每个失败落在记忆生命周期的哪一阶段（写入保持、检索召回、相近区分、
  边界安全、更新覆盖、导管服务），以及对应的可行动建议。
- **题目区分度**：同一道题在不同被测上是否出现分化。全员通过或全员失败的题**测不出东西**，
  这一节把它们点出来。
- **归因证据化**：上面那条「归因与改进建议」给的是**结论**，这一节回答**结论可不可信**——
  归因里有多少条是「看出来的」（有记忆快照 / 行动证据 / 神谕记录独立支撑），
  有多少条只是「从回答文本推出来的」。
  口径是 `有据支撑条数 ÷ 有归因条数`，验收线 80%。不同归因要的证据不一样：
  复用旧值要有快照里确实躺着那个旧值，该记没记要有快照里确实没有它，
  无响应要有行动证据，基础能力问题要有神谕记录。
  完整未支撑清单写在 `attribution.json`。
  **真实智能体这一栏常常很低，那是实话不是缺陷**：它的记忆在自己进程里，评测看不到快照，
  归因只能靠回答推断。想把它抬上来，给配置加 `stateDir`（升到 T2、快照可读）
  或跑的时候带 `--with-oracle`。
- **同题配对 + bootstrap**：两款主对比之间的差值配 95% 区间（B=1000，固定种子，可逐字节复现）。
  **区间跨 0 时结论写「未见显著差异」，不写成平手**，也不许把小数点后的差值当优势。
- **记忆增量 Δ**（需 `--with-control`）：同一份提问与判定，只把注入事实换成中性开场。
  两条臂通过率之差才是记忆带来的增量——没有对照臂，任何绝对通过率都无法排除
  「这题本来就能靠常识答对」。
- **神谕对照**（需 `--with-oracle`）：失败用例附上正确事实重问。仍答错 ⇒ 失败在理解或执行环节，
  与长期记忆无关，**移出记忆能力分母**。否则「模型本身不行」会被记成「记忆不行」，改进方向跑偏。
- **逐题一致率**（需 `--repeat N`）：每次都过 / 时过时不过 / 每次都败。
  总体标准差会平均掉「个别题反复翻面」，这一节把抖动落到题号上。
- **判定口径**：本轮规则版本、评委层是否启用及原因、评委缓存命中率。

---

## 3. 接入新智能体

### 3.1 先探测，再评测

```bash
litmus probe --agents-config examples/agents-openkylin.json --out out/probe
```

probe 在跑几百回合之前先花不到十回合探清**这个平台能被测到什么程度**，产出：

- `probe.md` / `probe.json`：逐条观测结论、生效能力、置信层级（T1 仅对话 / T2 记忆可观测 /
  T3 行动可观测）、以及「哪些用例在这个平台上测不了」；
- `agents-<id>.draft.json`：回填好 `capabilities` 与 `stateDir` 的配置草案，可直接喂给 `run`。

**为什么要这一步**：跨会话题要求被测对象能清空上下文只留长期记忆。平台做不到时，
答对可能只是窗口里还留着原文——判它通过是虚高，判它失败是给它扣一顶「我们测不了」的帽子。
两个都是假数字。probe 把这条边界提前变成已知事实。

### 3.2 五种接入类型

```jsonc
[
  { "id": "my-http", "name": "某 HTTP 智能体", "type": "http",
    "endpoint": "http://127.0.0.1:8080", "path": "/v1/chat",
    "resetPath": "/v1/session/reset", "timeoutSeconds": 120, "role": "primary" },

  { "id": "my-cli", "name": "某命令行智能体", "type": "command",
    "command": "bash", "args": ["scripts/my-agent-adapter.sh", "{sessionId}", "{question}"],
    "resetArgs": ["scripts/my-agent-adapter.sh", "{sessionId}", "--reset"],
    "sandbox": "isolated", "stateDir": "/home/user/.my-agent/memory",
    "sandboxSeed": ["~/.my-agent/config.toml", "~/.my-agent/.secret_key"],
    "capabilities": ["MEMORY_INSPECTABLE", "RESETS_CONTEXT"] },

  { "id": "my-acp", "name": "某 ACP 智能体", "type": "acp",
    "command": "/usr/local/bin/my-agent-acp",
    "homeEnvVar": "AGENT_HOME", "homeTemplate": "~/.config/my-agent",
    "timeoutSeconds": 180 },

  { "id": "my-mcp", "name": "某 MCP 智能体", "type": "mcp",
    "command": "/usr/local/bin/my-agent-mcp",
    "tool": "chat", "messageArg": "text",
    "homeEnvVar": "AGENT_HOME", "homeTemplate": "~/.config/my-agent",
    "timeoutSeconds": 180 },

  { "id": "reference", "name": "内置参考实现", "type": "builtin", "role": "primary" }
]
```

- `args` / `resetArgs` 支持占位符 `{sessionId}`、`{question}`、`{sandboxHome}`（仅 command 类型；
  acp / mcp 是常驻进程，参数只在启动时用一次，不做占位符替换）。
- **acp**（Agent Communication Protocol，stdio JSON-RPC）：凡是能被编辑器（Zed / VS Code /
  JetBrains）接上的 ACP 智能体都能直接测。`homeEnvVar` + `homeTemplate` 给每个会话一个独立
  家目录（凭据/配置从模板铺入），用例间长期记忆互不污染；**会话重置只丢 ACP 会话句柄、
  家目录保留**——跨会话用例测的正是这个边界。被测回合若要求客户端侧回调（sampling 等），
  适配器不代答，该回合按超时记「无响应」。
- **mcp**（Model Context Protocol，stdio 传输）：以 MCP server 形态暴露对话工具的智能体，
  一次 `tools/call` 即一个评测回合；工具名（`tool`，默认 `chat`）与消息参数名
  （`messageArg`，默认 `text`）可配。**会话重置 = 进程重启**（MCP 没有会话概念）：
  被测对象必须把长期记忆持久化在进程之外，否则跨会话用例测到的是「重启即失忆」——
  那是该智能体的实况，如实读，不是适配器的缺陷。
- **相对路径按启动 litmus 的目录解析**（如 `scripts/foo.sh`），不会因为被测进程被放进沙箱工作目录而失效。
  只有含 `/` 且在启动目录下真实存在的 token 会被锚定：靠 `PATH` 解析的程序名不动，提问文本更不会动。
- `sandboxSeed`：要从宿主复制进沙箱家目录的文件/目录（按末级名字落位，`~/.my-agent/config.toml` →
  `<sandboxHome>/config.toml`）。本地大模型智能体几乎都要读自己的凭据才起得来，而沙箱的 HOME 是空的。
  注入后权限收紧到仅属主可读写，且注入清单会写进 `manifest.json` 的 `isolation` 字段——
  **给了什么进沙箱，纸面上看得见**。源不存在会如实报「未找到」，不静默跳过。
- `sandbox`：`isolated`（默认，临时 HOME）/ `strict` / `none`（仅 command 类型）。**命令行被测会以你的权限执行**，
  不要用 `none` 跑不信任的程序。隔离层级会如实写进 `manifest.json` 的 `isolation` 字段；
  HTTP 接入不启动被测进程时，那里写的是「隔离责任在被测服务自身」——**留空会被读成「已隔离」**。
- `stateDir`：给出记忆落盘目录，「记忆有没有写进去」就从推断升成观测（T1 → T2）。
- `capabilities`：显式声明；与适配器自证能力取并集，非法名称直接报错。

### 3.3 跑

```bash
litmus run --agents-config my-agents.json --suite standard --out out/my-run \
           --with-control --with-oracle --parallel 4
litmus run-http --endpoint http://localhost:8089 --state-dir /path/to/memory --out out/real
```

`--parallel` 只影响耗时，不影响 token 总量。真实智能体上 `--with-control` 会让用例数与
token 翻倍，`--with-oracle` 只重问失败用例（上限 `--oracle-max`，默认 40）。

---

## 4. 评委层（可选）

规则层用归一化关键词匹配，**离线、确定性、零成本**，覆盖绝大多数用例。
剩下的是「关键词分不开」的情形——排除项出现在对照说明或否定语境里
（"不是 A，是 B"），关键词命中了但语义上并没有混淆或泄漏。

```bash
export LITMUS_LLM_API_KEY=...       # 依次尝试 LITMUS_LLM_API_KEY / AI_DASHSCOPE_API_KEY /
                                    # DASHSCOPE_API_KEY / OPENAI_API_KEY / DEEPSEEK_API_KEY
litmus run --judge --out out/judged
litmus selftest --judge             # 同时量规则层与评委层，κ 门禁生效
```

- 默认端点是 DashScope 兼容模式，模型 `qwen-plus`；可用 `--judge-endpoint` / `--judge-model` 换。
- **评委团多数决**：`--judge-model` 逗号分隔多个模型（如 `qwen-plus,deepseek-chat`）即组成评委团，
  每位独立请求、独立缓存，**严格多数**（> 半数）才判 PASS/FAIL；平票、弃权占多数、
  或某位输出不合约定都记不可判——评委团的分歧是信息，不硬判。人数取奇数，平票必然弃权。
  `--judge-endpoint` 只给一个则全体共用，给多个则与模型一一对应。
- temperature 0 + 固定 seed；判定结果落 `judge-cache.json`，同输入重复运行**命中缓存不再调用模型**。
- **密钥只从环境变量读，绝不写入任何产物、配置或日志**；报告里只记密钥来源名与模型指纹。
- 拿不到密钥就退回规则层，并把原因写进报告。**这条路径必须始终可用**——
  评测工具如果必须有 API Key 才能跑，那它在离线环境、内网、以及「我不想为了复核一个数字去申请额度」
  的场景里就等于不存在。
- 这类用例在没有评委时判 `UNKNOWN` 并给出归因 `NEEDS_SEMANTIC_JUDGE`，**不猜、不算失败**。

---

## 5. 题集档位

| 档位 | 题数 | 内容 |
|---|---|---|
| `basic` | 150 | 六维基础题（默认） |
| `security` | 88 | 高敏留存、要求删除、声明作废、临时方案、记忆投毒 |
| `pressure` | 30 | 长更新链、同构干扰、规模档 |
| `crosscut` | 15 | 跨维度交叉（更新×边界等） |
| `standard` | 238 | basic + security |
| `full` | 283 | basic + security + pressure + crosscut |

```bash
litmus validate-cases --suite full     # 数据治理：合法性与覆盖度，不合格退出码非零
litmus export-cases --suite full --out out/cases   # 导出题集，便于扩展或外审
litmus run --cases my-cases.json --out out/custom  # 外置题集（与 --suite 互斥）
```

题库是**静态可审**的，这是刻意的定位：题目要能被第三方逐条读过、指出错处。
不要为此改成生成式题库。

---

## 6. 改完复验（可接 CI）

改了判定规则、题库或报告口径之后，按这个顺序走：

```bash
# ① 尺子还准吗？用标注集量判定器自己，不接触任何被测对象
litmus selftest --out out/selftest           # 规则层门禁
litmus selftest --judge --out out/selftest   # 加上评委层，κ 门禁生效

# ② 已有运行的数字还对得上吗？重算全部哈希
litmus verify --from out/my-run

# ③ 口径变了，旧证据要不要重跑？不要——离线重评
litmus regrade --from out/my-run --out out/my-run-regraded

# ④ 新版本有没有回归？逐题比较，有红线则退出码非零
litmus compare out/run-v1 out/run-v2 --out out/cmp
```

### ① selftest

用随 jar 发布的标注集（真实运行里摘出的问答对 + 人工标签）量判定器，报一致率 /
Cohen's κ / **开口即对率** / 弃权率 / 逐条分歧。分歧分四种：假阳、假阴、
盲点（该弃权却开口）、弃权（该判未判）。

门禁只卡**能判的**项：评委层 κ 只有在评委真的启用时才算，没启用就写「不可判」而不是算它通过——
**用缺席的评委去证明尺子准，是这套工具在别处一律拒绝的动作**。

κ 必须与一致率一起报：两者会在同一批数据上给出不同结论，只看一致率会高估尺子。
标注集是**单人标注**，因此这个 κ 是「规则判定 vs 人工标注」，**不是**标注者间一致性——
报告里明写这一点，不含糊过去。

### ② verify

重算契约里每一项哈希并与目录比对，并用**契约记录的档位**重算题集哈希。
产物被改动、产物缺失、目录里混入未登记文件、题集不一致，都会指出是哪一处。

### ③ regrade

判定口径会变，而真实智能体一轮评测要几十分钟和真金白银的额度。没有离线重评，改一次口径
只有两个选择：重跑（贵，且被测对象自己可能也变了，于是「口径变化」和「能力变化」混在一起），
或沿用旧数字（等于口径版本号是装饰）。

regrade 用当前口径重判旧证据，逐条列出裁决变化。**不重跑被测对象**，
所以差值只能来自口径本身。三条硬约束：

- **只读源目录**，产物写进 `--out`；`--out` 与 `--from` 相同直接报错；
- **只重评 `role=answer` 的条目**，不靠「问答交替所以第二条是回答」这种顺序推断；
- **旧格式如实拒绝**：角色标注之前导出的证据流没有 `role`，此时报「无法离线重评」并说明原因，
  **不猜**。请用当前版本重跑一次，之后的运行都支持离线重评。

### ④ compare

从两侧证据流里**照抄**各自当时的裁决（不重判），只在两侧都已判定的题上配对。

红线（退出码 1）：口径不同、题集不同、缺契约、配对差值显著为负、**新增不可判 ≥ 1**。

**逐题回归本身不是红线**：真实智能体有抖动，单题翻面是常态，这类情况列进明细但不阻断。
门禁太紧的后果是所有人开始加 `|| true`，那比没有门禁更糟。

口径不同时它会拦住你并要求先 `regrade`——那种情况下差值同时包含「尺子变了」与「东西变了」，
报出来的回归是假的，而假回归会让人去查一个不存在的问题。

---

## 7. 常见坑

| 现象 | 原因 | 处理 |
|---|---|---|
| 跨会话题全判不可判 | 被测对象没有可复位的状态边界 | 先 `probe`；或提供 `resetArgs` / `resetPath` |
| 大量 `NO_RESPONSE` | 限流 429 / 超时 / 空回答 | 已内置指数退避重试；仍大量出现说明被测服务不可用。**这类失败不计入记忆能力评分** |
| `NO_RESPONSE` 且耗时只有十几毫秒 | 接入根本没起来，不是模型慢 | **先看行动证据里的退出码**（`artifacts/<agent>/<case>.md` 或证据流里 `type=action` 的行）：127 是命令/脚本找不到，1 且瞬间退出通常是适配脚本 `set -e` 下第一个失败命令 |
| 第一条用例能过、后面全失败 | 适配脚本依赖 `$HOME` 下的凭据，而沙箱 HOME 是空的；第一条侥幸命中了此前手工跑出来的目录 | 用 `sandboxSeed` 显式注入凭据文件，别让脚本去读真实主目录 |
| `regrade` 报「旧格式」 | 证据流没有 `metadata.role` | 用当前版本重跑一次 |
| `verify` 报「题集不一致」 | 用默认档位对了高档位目录 | verify 会按契约记录的档位重算；外置题集要加 `--cases` |
| 有效分比 `report.txt` 对不上 | 该次运行启用了 `--with-oracle` | 报告里是神谕调整**后**的数，证据流里留的是调整**前**的裁决；regrade/compare 会主动提示这一点 |
| 评委层没生效 | 环境变量里没有密钥 | 报告会写明原因并退回规则层，这是预期行为 |
| `rerender` 报「拒绝就地重渲染」 | 该目录已用 `manifest.json` 登记产物哈希，就地换图会让随后的 `verify` 报「产物被改动」 | 加 `--out <新目录>`：产物写新目录，源目录不动。新目录没有 manifest，不能拿去做对账 |

---

## 8. 平台支持现状（实测口径）

「全平台」的完整含义是**跨 OS + 跨语言 + 跨智能体框架**，而不是「我也支持 macOS」。
下表区分「实测过」与「纸面声明」——**没有真机验证的格子一律记为未验证**：

| 平台 | 状态 |
|---|---|
| Linux x86_64（openKylin 3.0 huanghe，OpenJDK 21.0.11） | **已实测**：run / verify / selftest / regrade / compare / probe 全部跑通；真实智能体（KylinBot CLI）在此格实测。`.deb` 也已实测到「不需要系统 Java」这一层：解包后在**空 PATH + 无任何 locale 变量**下跑完 basic 150 条（退出码 0，606 份产物），PATH 里没有 `java`，用的只能是包内 bundled runtime |
| 容器（无桌面 / root / 无 bwrap / 无 git，Ubuntu 26.04 镜像） | **已实测**：`./scripts/verify-container.sh` 一键复现——full 283 条 run + selftest + verify 全链路；命令行型被测接入（无特权隔离 + `sandboxSeed`）；`sandbox=strict` 缺 bwrap 时直接失败而非静默降级 |
| JVM 17（Temurin 17.0.20，容器内） | **已实测**：与 JVM 21 产出的 `report.txt` **sha256 逐字节一致**——「编译目标 17，17+ 均可运行」与「结果确定」都不是纸面声明 |
| Linux arm64 | 未验证（需确认 jpackage/JDK 可用性） |
| macOS | CI 矩阵持续验证中（`gate` job 首亮）；沙箱 `sandbox-exec` 层待真机补 |
| Windows | CI 矩阵持续验证中（`gate` job 首亮，`continue-on-error`：测试里的 `sh -c` 需适配 git-bash）；隔离手段待评估 |
| 信创架构（申威 / 龙芯） | 未验证。Java 有发行版，Rust 大概率没有——这是不迁 Rust 的实证理由 |
| 远程目标机（`ssh://`） | 未实现 |

已实测 3 格（宿主 x86_64 / 容器 / JVM 17）；macOS / Windows 由 CI 三平台矩阵持续验证（首亮中，Windows 允许失败不拦 PR）。
下表里每一格「已实测」都能用仓库里的脚本当场复现，这是它和「理论上能跑」的区别。

```bash
./scripts/verify-openkylin.sh     # 宿主格：环境采集 + 全量测试 + 评测
./scripts/verify-container.sh     # 容器格与 JVM 17 格：需要 docker 与网络（拉两个 JRE 镜像）
```

协议本身（[`SPEC-EVIDENCE.md`](SPEC-EVIDENCE.md)）是跨语言的：任何语言写的 runner，
只要产出合格的 `evidence/*.jsonl` 与 `manifest.json`，就能直接用本仓库的评分器、
对账器、离线重评与回归门禁。这一条**不依赖上表里的任何一格**。

## 9. 汇总榜单（多份运行 → 一张表）

```bash
litmus leaderboard out/hermes-01 out/kylin-01 samples/full-final-150 --out board/
# 产出 board/leaderboard.txt（可贴材料）与 board/leaderboard.html（浏览器打开即见）
```

榜单的可信不来自榜单作者，来自**每行都能回溯到一份可对账的运行**：

- 分数取自各目录 `manifest.json` 里的原值（有效分 / 覆盖率 / 悲观下界），**不重算**——
  每行都能用 `litmus verify --from <目录>` 当场对账，行内直接标注对账状态；
- **判定口径或题集混用 ⇒ 榜单顶部显著警告**：那种情况下排名只是罗列，不是比较。
  先 `litmus regrade` 把旧证据统一到当前口径，再进榜单；
- 没有 `manifest.json` 的目录（旧版运行、样例目录）按 `report.txt` 解析六维平均分，
  行内标注「无契约，未验证」——不静默丢弃，也不冒充已验证；
- `--out` 不能指向任何来源运行目录：榜单对它们只读，写进去会毁掉运行契约的产物哈希。

排序规则：有效分降序 → 覆盖率降序 → 悲观下界降序 → 名称。无契约行按六维平均排序，口径列写「未知」。
