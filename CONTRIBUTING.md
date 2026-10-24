# 贡献指南

## 环境

```bash
./mvnw clean package        # JDK 17+ 编译，运行时需要 17 及以上（开发机是 21）
./mvnw test                 # 全部测试，当前 227 条，必须在提交前全绿
```

核心库只允许依赖 **Jackson + slf4j**。新增第三方依赖前请先在 issue 里说明理由——
这个工具要能被塞进 `.deb` 和离线信创环境，依赖面就是它的部署面。

## 提交前必须跑的三件事

```bash
java -jar target/memory-bench-1.0.0.jar selftest                    # 尺子自证：一致率 / κ / 弃权率 / 盲点
java -jar target/memory-bench-1.0.0.jar verify  --from <一次真实运行目录>   # 产物哈希对账
java -jar target/memory-bench-1.0.0.jar compare --from <旧运行> --to <新运行>  # 回归门禁，退出码非 0 就是拦下了
```

`compare` 退出码 1 而你认为不是回归时，**不要绕过它**：在 PR 里写清楚为什么这次差异可以接受，
让人来判断，而不是让门禁闭嘴。

## CI 跑的是同一套门禁

`.github/workflows/ci.yml` 把上面三件事搬到了推送触发上，另加几道：

| 步骤 | 断言 |
|---|---|
| 构建 | 版本戳写进 jar（`litmusVersion` / `gitCommit` 不得是 `unknown` 或残留 `${...}`） |
| 全量测试 | 227 条全绿 |
| `selftest` | 规则层 **盲点数必须为 0**，并打印 κ / 开口即对率 / 弃权率 |
| `run --suite full` | 内置两款跑完 283 条 |
| `verify --from` | 全部产物哈希一致 |
| `compare` | 同代码跑两次自比必须通过（退出码 0） |
| `regrade` | 重评前后对源目录做 `sha256sum` 全量 diff，证明**只读** |
| `attribution.json` | 归因证据化必须达标（内置是记忆注入模式，比例必须为 1.0） |
| 容器矩阵 | `scripts/verify-container.sh`：容器格 + JVM 17 格 |

评委层那一步（`selftest --judge`）只在仓库配了 `LITMUS_LLM_API_KEY` 时才跑；没配就跳过，
不会让 CI 变红——**门禁不许依赖只有维护者才有的凭据**。

> **如实说明**：这套 workflow 目前**还没有过一次绿色的真实运行**（仓库尚未推送到带 Actions 的远端）。
> 表里每一步都在本地按同样顺序跑通过，但"本地跑通"不等于"CI 跑通"——
> 第一次推送后请回来把这一行改成实际的运行结论，别让它一直挂着。

## 六条硬规矩

### 1. 改判定逻辑 = 改口径，必须换版本号

判定口径写在 `CaseJudge.RULES_VERSION`（当前 `2026.10-c`）。任何会影响「同一批证据被判成什么」的改动
——阈值、关键词匹配、分母、弃权条件——都必须：

1. 把 `RULES_VERSION` 升到下一个字母；
2. 在 [`CHANGELOG.md`](CHANGELOG.md) 的「判定口径历史」里写清改了什么、哪些已发布数字作废；
3. 用 `litmus regrade --from <旧运行目录>` 把旧证据按新口径重算一遍，把差值贴进 PR。

**为什么这么严**：口径一变，历史通过率就不再可比。悄悄改算法等于让所有引用过旧数字的人拿着错的结论走。
`ProtocolSchemaTest` 会检查 schema 里的 `rulesVersion` 模式与代码常量一致，`compare` 会拒绝跨口径直接比较。

### 2. 改证据格式 = 改协议，必须同步三处

证据流与运行契约的格式定义在 [`docs/SPEC-EVIDENCE.md`](docs/SPEC-EVIDENCE.md)，
机器可读版在 [`docs/schema/`](docs/schema/)。改动必须同时更新规范、schema 和代码常量——
`ProtocolSchemaTest` 会在三者漂移时直接失败（它比对 enum 全集、字段集合与 `additionalProperties`）。

新增字段优先做成**可选**：老证据必须仍能被 `regrade` 读回。读不回来时如实报错，
**绝不允许猜**——猜出来的数字不会报错，只会是假的。

### 3. 新增派生产物，必须在运行契约之前落盘

`manifest.json` 哈希了运行目录里所有其他文件，所以任何新增的派生产物（报告片段、统计明细、缓存）
都必须**在 `RunManifest.of(...)` 之前写完**，否则它自己就不在对账范围内——
「这个数是怎么算出来的」这句话本身失去了对账依据。`attribution.json` 与 `judge-cache.json` 都是这么接的。

同时：`regrade` 不会重算派生产物。凡是「按当时判定结果统计出来」的文件，都要在 `Regrade.run` 里
加一条 note 说明它已过期（见 `attribution.json` 那段），不能让读者拿旧文件当现状。

同一枚硬币的反面：**新增子命令如果读一个已有运行目录，就必须对它只读。**
`manifest.json` 哈希了目录里所有其他文件，回写 `radar.svg` / `report.html` 这类登记过的产物，
等于用一条辅助命令毁掉那次运行的对账能力（`rerender` 真这么干过，见 `PITFALLS.md` §8.9）。
规矩：默认写 `--out`；不给 `--out` 就地写之前先检查 `manifest.json` 是否存在，存在就**直接拒绝**
并说明该加哪个参数——"画完再提醒你 verify 会红"不算合规。

### 4. 加题目必须过区分度准入

新用例加进去以后跑一次 `run`，看报告末尾的「题目区分度准入检查」。
全过（太简单）、全败（期望不可达）、全不可判（接入有问题）的题会被点名。
被点名的题要么加难期望，要么换更冷僻的事实，要么修接入——**不要为了让报告好看而删题**。

### 5. 新增"质量指标"必须能掉下去

归因证据化比例、覆盖率、κ、区分度这类指标，加进来的时候必须同时给出**一个会不达标的场景**
并写成测试（见 `AttributionEvidenceTest`：快照不可读、旧值不在库里、缺行动证据、缺神谕记录、
能力声明自相矛盾，五种情形都必须记为未证据化）。**只会输出 100% 的指标等于没有指标**——
它给读者的是虚假的确信，比没有这个数更糟。

### 6. 任何会落盘的文本，其排序键不许来自不稳定来源

工具的第二条命是「同一输入跑两次，产物逐字节一样」——第一条件是尺子有效，第二条件才是可复现。
所以凡是会影响**落盘文本次序**的比较键，只许用结构上确定的量：

- 不许用 `System.currentTimeMillis()` / `Instant.now()` 当同分回退键。
  一批写入落在同一个毫秒里还是跨毫秒边界，取决于机器快慢——`topK` 截断掉谁因此变成随机。
  要「新的优先」就用**写入序号**（追加型存储里就是行号），它天然等于时间次序但不读时钟。
- 不许用 `Map.copyOf` / `Set.copyOf` / `Map.of(k,v,…)` 承载要逐行打印的次序。
  Java 的不可变集合**每个 JVM 进程启动换一个随机盐**，迭代次序在两次运行之间就能不同。
  要不可变又要保序：`Collections.unmodifiableMap(new LinkedHashMap<>(…))`。
- 同理不许依赖 `UUID`、`identityHashCode`、`Files.list()` 的目录枚举次序。

**验证方式只有一种：跑两次比 sha256，而且要在最大题集上比。**
`basic 150` 上曾长期"一切正常"，两个缺陷都只在 `full 283` 显形——用例太少、注入太松，撞不上边界。
CI 与 `scripts/verify-container.sh` 里的跨 JVM `report.txt` sha256 断言就是这条的执行机构，
它红过一次就不要当作摆设。

## 代码风格

- 注释只写**为什么**，不写做什么；非显然的约束、踩过的坑、会被误读的行为才值得占一行。
- 判定层与安全层宁可**弃权 / 报错**，也不给一个看起来合理的默认值。这个项目的价值就在于数字可信。
- 报告文案面向不懂统计的人：任何百分数旁边都要能被追到分子分母。

## 目录约定

| 路径 | 内容 |
|---|---|
| `src/main/java/io/github/agentlitmus/core/` | 判定、口径、重评、回归门禁 |
| `src/main/java/io/github/agentlitmus/agent/` | 接入层（HTTP / 命令行 / 内置）与进程隔离 |
| `src/main/java/io/github/agentlitmus/dataset/` | 题库与分档套件 |
| `docs/` | 规范与用法（`SPEC-EVIDENCE.md` / `USAGE.md` / `roadmap.md`） |
| `samples/` | 入库的样例证据，是**冻结样本**：除非协议改版，不要重新生成 |
| `out-*/`、`litmus-out/` | 本地运行产物，已在 `.gitignore` 里，不要提交 |

## PR

标题用 `feat:` / `fix:` / `docs:` / `refactor:` 前缀（与仓库历史一致）。
描述里请写清：动机、是否动了判定口径、以及跑过上面三件事的结果。
涉及真实智能体实测数字的 PR，请附上运行目录里的 `manifest.json`，否则数字无法复核。
