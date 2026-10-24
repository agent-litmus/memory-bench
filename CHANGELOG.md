# 变更日志

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

**这个项目的特殊约定**：判定口径（`rulesVersion`）独立于工具版本演进。口径一变，历史通过率就不再可比，
所以每次口径变化都在下面单列一节，并写明「哪些已发布数字作废、怎么重算」。
工具版本号只描述能力，不代表口径。

---

## 判定口径历史

| 口径 | 首次出现 | 判定值 | 分母 | 通过率含义 |
|---|---|---|---|---|
| `2026.10-a` | 0.1.0 | 两值（通过/不通过） | 全部计划用例 | 通过 / 计划 |
| `2026.10-b` | 1.0.0 开发期 | 三值（通过/不通过/不可判） | 全部计划用例 | 通过 / 计划（不可判计为 0 分） |
| `2026.10-c` | **1.0.0（当前）** | 三值 | **已判定用例** | 有效分 = 通过 / 已判定，另出覆盖率与悲观下界 |

### `2026.10-c`（当前口径）改了什么

1. **不可判移出分母。** 无响应（空回答 / 请求失败 / 限流 / 超时）与能力门控跳过不再算作失败，
   而是记为 `UNKNOWN` 并移出评分分母。理由：这两种情况下判定根本没发生，把它算成 0 分
   等于用「评测导管坏了」去扣「被测对象的记忆能力」。
2. **一个通过率拆成三个数**，任何单独一个都能被误读：
   - 有效分 = 通过 / 已判定 —— 能力有多强
   - 覆盖率 = 已判定 / 计划 —— 这个分数建立在多大比例的样本上
   - 悲观下界 = 通过 / 计划 —— 最坏情况下的下界（等价于 `2026.10-b` 的算法）
3. **新增两类移出分母的原因**：`BASE_OR_EXEC`（接入或执行本身失败）、`NOT_OBSERVABLE`（平台能力不足，
   无法区分「靠上下文」与「靠长期记忆」）。
4. **裁决写进结构化字段** `metadata.verdict`，不再靠正则从裁决文本里抠。旧格式仍能读（兼容回退），
   但读不出来时如实报错，不猜。

### 口径变更作废了哪些已发布数字

`2026.10-a` / `2026.10-b` 时代公布的单一通过率**不可与 `2026.10-c` 的有效分直接比较**。
受影响的历史数字见 README「新旧口径对照」一节；重算方式是 `litmus regrade --from <旧运行目录>`，
它只读旧目录里的证据流，不重新调用被测对象，因此差值只能来自口径，不能来自能力变化。

---

## [1.0.0] - 2026-10-24

从「能跑出通过率」升级为「跑出的数字可以被质疑、被复核、被回归拦截」；
接入面从 openKylin 两款真实智能体扩展为 **HTTP / CLI / ACP / MCP 四通道、配置即接入**。

### 新增

- **通用 ACP 适配器（`type: acp`）**：任意 ACP（Agent Communication Protocol，stdio JSON-RPC）
  智能体配置即接入——凡是能被编辑器（Zed / VS Code / JetBrains）接上的 ACP 智能体都能直接测。
  `homeEnvVar` + `homeTemplate` 给每个会话独立家目录（凭据从模板铺入），用例间长期记忆互不污染；
  会话重置只丢 ACP 会话句柄、家目录保留（跨会话用例的正确边界）。内置 Hermes 驱动保持原实现不变。
- **MCP 适配器（`type: mcp`）**：以 MCP server（stdio 传输）暴露对话工具的智能体一次
  `tools/call` 即一个评测回合；工具名与消息参数名可配（默认 `chat(text)`）。
  MCP 没有会话概念，重置 = 进程重启——长期记忆须由被测方持久化在进程外，
  否则跨会话用例测到的「重启即失忆」是该智能体的实况；这条语义边界写进了文档而不是藏在代码里。
- **共享 stdio JSON-RPC 底座**：`JsonRpcPeer`（后台读线程 + 超时队列 + 通知攒集，
  机制与 Hermes 实测同源）与 `StdioRpcAnswerer`（每会话一进程、存活上限淘汰、异常降级为无响应）。
  端到端测试用单文件 Java 假服务器（`java FakeXxxServer.java` 拉起真实子进程）覆盖：
  思考块不混入答案、家目录注入真的生效、重置语义、流式抽取与 isError 路径。
- **CI 主门禁挂 push / PR 并扩为三平台矩阵**（Linux / macOS / Windows；公开仓库 runner 免费、
  零密钥零模型额度）。跨平台矩阵从「未验证」变「持续验证」；Windows 首次点亮
  `continue-on-error`（测试里有 sh -c，git-bash 不保证提供），修绿后摘除。
  评委层与容器格维持手动流水线不变。
- **三值判定与覆盖率口径**：`Verdict.PASS/FAIL/UNKNOWN`，有效分 / 覆盖率 / 悲观下界三口径并列输出。
- **Python 用户免装 Java（pip 薄壳）**：`pip install agentlitmus`——首次运行自动从 Release 下载
  当前平台自包含可执行（sha256 对账 checksums.txt），之后离线转发命令；判定器保持单一实现，
  不复刻任何评测逻辑。支持 `AGENTLITMUS_BINARY`（已有部署直连）与 `AGENTLITMUS_DOWNLOAD_BASE`（镜像）。
- **五平台自包含二进制 + 多架构 Docker 镜像**：Release 发布时 CI 自动构建
  macos arm64/x64（Intel runner 已退役，x64 经 Rosetta 交叉打包）、linux arm64/x64、windows x64
  的 app-image（内置运行时，产物 `--help` 自检后才上传）并附 checksums.txt；
  Docker 多架构镜像推 ghcr.io（Release 打版本号 + latest，试跑打 edge）。
  打包脚本本地实测：macOS arm64 48.5MB，selftest/run/verify 全链路通过；
  pip 壳离线全流程（下载→校验→解压→转发→二次免下载）通过；Docker 本机构建运行产物落卷通过。
- **macOS（aarch64）真机格点亮**：259 条测试全绿 + selftest / run / verify / regrade /
  compare / leaderboard / rerender 全链路实测，basic 150 双跑 `report.txt` sha256 逐字节一致
  （与 Linux x86_64、JVM 17/21 同列）；平台矩阵 3 格 → 4 格，USAGE §8 如实更新。
- **README 特性叙事对齐 1.0.0**：新增三值判定与三口径小节、§4.6 运行契约与可信链（五命令闭环），
  创新点七条扩为九条（三值口径、运行契约、评委团多数决、四通道接入），产物表补 manifest 与
  artifacts；SOLUTION 副标题改为跨平台定位；双 README 加 CI 徽章；USAGE 平台矩阵如实标注
  macOS / Windows 进入 CI 持续验证（首亮）。
- **题库分档**：basic 150 ／ security 88 ／ pressure 30 ／ crosscut 15；
  `standard` = basic + security = 238，`full` = 283。新增安全档（提示注入、越权持久化、隐私边界）、
  压力档（长上下文、相似事实干扰）、交叉档（更新 × 边界复合），以及对照臂、等价变体与虚构实体用例。
- **运行契约 `manifest.json`**：记录题集哈希、口径版本、工具版本、构建提交号、环境与全部产物哈希；
  `litmus verify` 逐条对账，任何事后篡改都会暴露。
- **无特权进程隔离**：被测智能体默认跑在独立 HOME/TMP + 环境变量白名单（含 KEY/TOKEN/SECRET 的一律不透传）
  + 超时杀整棵进程树；探测到 `bwrap` / `sandbox-exec` 时可用内核级隔离，要求 `strict` 而工具缺失时直接失败，
  不静默降级。
- **`sandboxSeed`**：本地大模型智能体需要读自己的凭据才起得来，而沙箱的 HOME 是空目录。
  现在可以显式声明要注入的宿主文件，注入后权限收紧到仅属主可读写，并在隔离说明与运行契约里如实披露。
- **证据做硬**：记忆状态快照（前后对比）、进程级行动证据（命令行 / 退出码 / 耗时 / 是否超时）、
  神谕对照臂（同题双臂，把「记忆带来的增量」从绝对分里分离出来）。
- **归因证据化比例**：三个口径管的是**分数**可不可信，这个数管的是**结论**可不可信。
  统计「失败归因里有多少条由回答文本之外的独立证据支撑」（目标 ≥80%），逐类给出判据
  （旧值未更新要快照里真有旧值、无响应要有行动证据、基座/执行要有神谕记录…），
  未证据化的逐条列出「缺什么证据」，明细写进 `attribution.json`。
  记忆注入模式 full 283 实测 degraded **162/162 = 100%**；对话模式且不给 `stateDir`、
  不加 `--with-oracle` 时比例会如实掉下去——**指标能掉下去，它才是指标**。
- **数字有意义**：题目区分度准入检查（全过 / 全败 / 全不可判的题被点名）、同题配对 bootstrap
  （B=1000，固定种子，95% 百分位置信区间 + 符号检验）、`--repeat` 一致率。
- **尺子自证 `litmus selftest`**：56 条标注金集上测规则层与评委层的一致率、Cohen's κ、开口即对率、
  弃权率与盲点数。当前评委层 κ = 0.901。
- **语义评委层 `--judge`**：OpenAI 兼容接口，temperature 0 + 固定 seed，按（口径版本, 评委指纹, 评分标准, 回答）
  缓存；密钥只从环境变量读，不写入任何产物。规则层判不开的用例才交出去，弃权优先于瞎猜。
- **离线重评 `litmus regrade`**：换口径重判同一批证据，逐条列出裁决变化。对源目录只读。
- **版本回归门禁 `litmus compare`**：比较两次运行，显著回归 / 新增不可判 / 口径不一致 / 题集不一致直接拦下（退出码 1）；
  单题翻面只列明细不拦人（可能是抖动）。
- **协议规范**：[`docs/SPEC-EVIDENCE.md`](docs/SPEC-EVIDENCE.md) 加两份 JSON Schema
  （[`evidence.schema.json`](docs/schema/evidence.schema.json)、[`manifest.schema.json`](docs/schema/manifest.schema.json)），
  并有测试守着 schema 与代码常量不漂移。第三方语言只要按协议写证据，就能被同一套尺子重评。
- **构建期版本戳**：`litmusVersion` 与 `gitCommit` 在打包时写进 jar，运行契约里不再是 `unknown`。
- **`.deb` 打包**：`scripts/build-deb.sh`，安装后注入 `/usr/bin` 软链与桌面图标。
- **CI 门禁流水线**：`.github/workflows/ci.yml`，只挂 `workflow_dispatch`（手动触发），
  **不挂 push**，并带一个默认关闭的总开关 `run_ci`——不点名它，两个 job 全部跳过，
  不进入执行也就不计 Actions 分钟（私有仓库 Free 计划每月 2000 分钟；公开仓库 Linux runner 免费）。
  `run_container_matrix` 同样默认不跑。评委层在未配 `LITMUS_LLM_API_KEY` 时打印一句跳过并 `exit 0`。
  CI 只跑内置被测，不接真实智能体，所以跑它不花 token 钱。配置与额度对照表见
  `CONTRIBUTING.md`「想跑的时候，怎么开、要配什么」。
- **CLI 子命令化**：千行大类 `Cli` 拆成 `cli/` 包——一个命令一个类 + 注册表分发，
  入口只留控制台编码兜底、分发与统一错误出口；新增命令 = 实现接口 + 注册一行 + 帮助一行，
  分发测试守着「注册的每个命令都必须出现在帮助文本里」。
  参数解析同步统一为「带值参数恰好消费下一个 token、缺值立即报错」——旧解析有两套取值模式，
  缺值时会把 `--cases` 自己当成文件名、或把下一个开关当值，直到运行期才以错误的方式炸出。
- **静态榜单 `litmus leaderboard`**：多份运行目录汇总成一张静态榜单（txt + 自包含 html），
  是公开榜单路线的第一块砖。分数取自各目录 manifest 原值、不重算；
  口径/题集混用与无契约目录都在榜单顶部显著警告、行内标注对账状态——榜单可信来自
  「每行可回溯到一份可对账的运行」，而不是相信制榜的人。
- **评委团多数决**：`--judge-model qwen-plus,deepseek-chat` 逗号分隔即组评委团；
  每位独立请求、独立缓存（缓存键含各自指纹，不串味），**严格多数**（> 半数）才判 PASS/FAIL，
  平票、「不可判」占多数、输出不合约定一律记不可判——评委团的分歧是信息，抹平它等于
  花多份钱得到一份瞎猜。单评委路径与旧版行为逐字一致。
- **渲染层 golden 快照测试**：`RadarChart` / `HtmlReport` 固定输入 ⇒ 固定 sha256
  （报告时间戳归一后参与哈希）；渲染全部改 `Locale.ROOT` + 固定 `\n` 换行——
  小数点不再被本地化、SVG 不再吃平台换行符，跨 locale / OS 字节一致。
  渲染从此是纯函数：样式改动必须是有意的（更新 golden 哈希），意外引入的不确定性会被测试拦下。

### 修复

- **仓库里缺了两个从未提交的类，fresh clone 一直编译不过**：`evidence/Transcript`
  （逐用例 Markdown 产物落盘）与 `evidence/EvidenceIntegrity`（证据流逐行 seq / sha256 自检）
  被三个已提交类引用却不在仓库里。根因是 `.gitignore` 里裸写的 `evidence/`
  （本意忽略运行产物目录）把源码包 `src/.../evidence/` 一并排除——新文件在 IDE 提交面板里
  根本不出现，推送方毫无察觉，任何人 clone（含 CI、别人的机器）都得到编译失败的仓库。
  已加 `!**/src/**/evidence/` 豁免，并按 `docs/schema/evidence.schema.json`、全部调用点与
  既有 javadoc 的交叉契约重建两个类；重建后 `run` + `verify` 全链路实测：
  basic 150 的 305 份产物对账全部一致。

- **上一次重建把证据自检做弱了，而唯一的契约测试还在未跟踪区**：补回的 `EvidenceIntegrity`
  只有 `checkFile`，丢了 `check(List<Evidence>)`、`Report.toText()` 与 `evidence.schema.json`
  的字段合法性校验（`type` 枚举、`sessionId` 非空、`timestamp` 为正）——「伪造一条证据」和
  「证据流被程序写坏」从此查不出来；`EvidenceContractTest` 因引用这些方法而**编译不过**，
  测试源码集整片坏掉，而它没入库，所以 fresh clone 与 CI 都看不见这片缺失的覆盖。
  一并修掉自检自身的三处误报/失明：
  ① 旧实现先自增期望序号再跳过空行，于是流中间一个空行会让**其后每一行**都报「断号 + 哈希不匹配」
  ——篡改检测报错误导，代价等同于对篡改失明；现在空行只跳过、不占期望值，且报**物理行号**
  （能直接 `sed -n '<N>p'` 打开现场）。② 整份被删空时没有断号可查，旧实现返回「通过」；
  现在空流自己报警。③ `check` 与 `checkFile` 共用同一套判据，杜绝「当场通过、离线复核报错」
  这类永远说不清的分歧。契约测试随之入库并补 3 条回归。
- **用例产物文件名是多对一映射，会静默覆盖证据**：`Transcript.fileNameOf` 把所有不安全字符折叠成
  `-`，`case-报销规则` 与 `case-报销流程` 都变成 `case.md`，后写的把先写的吃掉，而运行契约只登记
  最后那一份——对账看起来完全自洽，证据却少了一半。自定义用例（`--cases`）用中文 ID 就会踩中。
  现在凡发生过折叠就追加会话 ID 的哈希后缀：同 ID 仍得同名（可复现不破），但不再有两个 ID 撞进
  同一个名字；纯 ASCII 的名字逐字节不变，历史产物与契约不受影响。

- **内置被测不是确定性的**（本文件最该被记住的一条）。两处写法让「同输入同输出」悄悄失效，
  破坏的正是本工具的核心承诺——内置 `reference` / `degraded` σ=0、跨 JVM `report.txt` 逐字节一致：
  ① `AttributionEvidence.Result` 的紧凑构造器用 `Map.copyOf` 保存按归因类别的细分。
  Java 的不可变集合**每个 JVM 进程启动换一个随机盐**，迭代次序因此不稳，
  而报告按这个 Map 逐行打印，于是两次运行的 `report.txt` 只差 4 行顺序——数字全对，字节不同。
  改为 `Collections.unmodifiableMap(new LinkedHashMap<>(…))`。
  ② `FileLongTermMemory.recall` 的相关度同分回退键是 `updatedAt`，而它来自
  `System.currentTimeMillis()`。一次注入十几条事实时，它们落不落在同一个毫秒边界里纯看机器快慢，
  于是 `topK=5` 截断掉哪几条变成随机：`p5`（长期保持）在两次完全相同的运行间翻面，
  `reference` 总体从 100.0% 掉到 99.6%。改为按**写入次序（会话记忆文件里的行号）**倒序——
  `write` 追加、`update` 把替换条目追加到末尾，行号天然就是时间次序，语义没变但不再读时钟。
  **两处都不报错、单跑一次全对，只有"跑两次比 sha256"才暴露**；
  而且 basic 150 上一切正常，缺陷只在 full 283 显形——此前"σ=0"的结论是在小样本上得出的。
  抓出来的是门禁（`verify-container.sh` 的跨 JVM sha256 断言），不是人。
  补 4 条回归测试（`tieBreakFollowsWallClock` 的反例、重复召回稳定性、`byCause` 保序契约 ×2）。
- **KylinBot 适配器会被被测对象自己生成的配置遮蔽**：适配器按「`$HOME/.kylinbot` 里有
  `config.toml` 就用它」选凭据源，而 `kylin-bot` 运行中会自己创建 `$HOME/.kylinbot/config.toml`
  （不含 `.secret_key`）。从那一刻起每个**新会话目录**都在拷第二份时 `set -e` 退出 1（8ms，
  根本没启动 kylin-bot）。表现是主臂尾部 19 条连续「无响应」+ 对照臂 102 条全灭，
  看起来像配额耗尽。现在候选目录必须**成对**校验、两份都拷成功才一起落位（不留半成品目录），
  找不到就 `exit 3` 并在 stderr 写清试过哪些路径。
- **`verify-container.sh` 自己把断言废掉了**：命令行型被测那一格在容器里就
  `| grep -E "覆盖率"` 过滤了输出，后面再对同一份日志断言归因证据化的 basis 必然失败，
  而报错信息长得像被测对象坏了。断言用的日志不要提前过滤。
- **沙箱改掉工作目录导致相对路径失效**：`command` / `args` 里的相对路径（如 `scripts/kylinbot-adapter.sh`）
  在沙箱内解析不到，退出码 127，而报告上只显示「无响应」——接入坏了和被测对象答不出来长得一模一样。
  现在配置里的相对路径按启动 litmus 的目录锚定；提问文本不受影响（锚定发生在渲染之前）。
- **能力门控跳过时不写裁决产物**：这类「不可判」在证据流里没有记录，离线重评与跨运行比较会把它看成
  「压根没这道题」，于是覆盖率下降变得不可见。现在跳过也留一条 `kind=judgment` 的证据。
- **`compare` 输出 `-0.0`**：无差异时显示成 `-0.0 个百分点 95%CI [-0.0, -0.0]`，看着像回归。已归零。
- **重评读回裁决靠正则**：改为优先读结构化 `metadata.verdict`，文本只作旧格式兼容。
- **没有 locale 的环境里控制台中文全变问号**：POSIX/C locale（`env -i`、CI 容器、最小化信创服务器）下
  JVM 按 ASCII 编码 `System.out`，而同一次运行落盘的 `report.txt` 完全正常——
  「日志坏、产物好」的自相矛盾现象会把排查引到落盘代码上，而落盘代码没问题。
  现在只在**当前控制台编码连「测」都编不出来**时把 `System.out/err` 换成 UTF-8，
  因此 Windows 的 GBK 控制台不受影响（GBK 编得出中文，无条件换反而会制造真乱码）。
  编码属性按 `stdout.encoding` → `sun.stdout.encoding` → `native.encoding` → `file.encoding` 依次探测，
  Java 17 上没有第一个。
- **`scripts/build-deb.sh` 产出的 deb 是错的（但能装能跑）**：
  ① jar 名写死 `-SNAPSHOT`，与 pom 里的 `1.0.0` 已经对不上，版本号改为只从 `pom.xml` 读一次；
  ② `jpackage --input target` 会把 `classes/`、`test-classes/`、`surefire-reports/` 一起打进
  `/opt/agent-litmus/lib/`，改为只暂存可执行 jar；
  ③ 工作区脏时 `gitCommit` 仍写成干净的提交号，等于声称产物出自一个不含这些改动的提交，
  现在标成 `<hash>-dirty`。
- **`rerender` 就地改写源目录，把那次运行的对账能力弄坏了**：`radar.svg` 与 `report.html`
  内嵌的 SVG 都在 `manifest.json` 的产物哈希里，所以「只想换张图」之后 `verify` 会报
  两条「产物被改动」——数据一个没动，对账却坏了。现在 `--out` 真正生效（源目录只读），
  不给 `--out` 且目录里已有 `manifest.json` 时**直接拒绝**并说明该加哪个参数；
  临时目录（无哈希可护）仍允许就地。顺带分开两处提示：「HTML 里没有完整 `<svg>` 块」
  与「替换后内容逐字节一致、无需改写」原来共用一句「未找到可替换的 SVG 块」，
  第二次运行必然报出那句假话。详见 `PITFALLS.md` §8.9。

### 验证

- **`.deb` 在 openKylin 3.0（huanghe）/ x86_64 上实测通过**，不是"理论上能跑"：
  解包后在**空 PATH + 无任何 locale 变量**的环境下跑完 basic 150 条（退出码 0，
  两款内置被测，606 份产物，`attribution.json` 齐全，控制台中文正常）。
  PATH 里没有 `java`，因此用的只能是包内 bundled runtime——「装 deb 不需要系统预装 Java」这条
  到此为止是实测结论。
- **可复现性闸门（容器 + 跨 JVM）本轮全绿**：`scripts/verify-container.sh` 退出码 0，
  full 283 三条路径（宿主 / 容器 / JVM 17）`report.txt` sha256 逐字节相同
  （`9b53c083c8271f2b…`）。此前这条是断的——修复过程见 PITFALLS §8.8。

### 文档

- 新增 [`docs/USAGE.md`](docs/USAGE.md)（不用 Key 试用 / 接入新智能体 / 读报告 / 改完复验）
  与 [`docs/SPEC-EVIDENCE.md`](docs/SPEC-EVIDENCE.md)。
- USAGE 里如实写明平台支持现状：**只有 Linux x86_64 已实测**，arm64 / macOS / Windows / 容器 / 信创 / ssh
  为未验证或未实现。协议本身与语言、平台无关，但「协议可移植」不等于「本工具已在你平台上跑通」。
- **全量文案产品化**：源码 javadoc、报告内嵌文案、脚本提示与全部文档统一为工具与使用者的口径，
  openKylin 仅作为已实测的运行平台出现。用例正文里的「需求评审」（虚构公司的会议名）与
  判定层的「评委」（LLM 裁决）不在其列。后续提交又把早期的三份战略文档
  （旧 `docs/roadmap.md`、`docs/directions.md`、`submission/`）整体移出仓库，
  代之以公开版 `ROADMAP.md`。
- `HtmlReportGoldenTest` 的 golden 随上述文案更新为 `363ae74df78df2d6…`（原 `4aea8ecca1a76896…`）。

## [0.1.0] - 首个可用基线

- 内置 150 条用例（六维各 25，含 20 条跨会话），两值判定，单一通过率。
- HTTP / 命令行 / 内置三种接入方式，配置化零代码接入新智能体。
- 实测过 reference ／ KylinBot ／ degraded ／ Hermes 四款，公布数字见 README「新旧口径对照」。
- 记忆失败归因层、稳定性证据（`--repeat`）、覆盖度校验、主对比 / 附加角色分离。

[1.0.0]: https://github.com/agent-litmus/memory-bench/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/agent-litmus/memory-bench/releases/tag/v0.1.0
