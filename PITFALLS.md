# 踩坑经验总结

> 本文记录用 memory-bench 评测**真实智能体**（openKylin 生态的 Hermes Agent）全过程踩到的坑。
> 每条都按「现象 → 根因 → 处理 → 可迁移的教训」组织，便于复用。

## 目录

- [1. 一条值钱的排查心法：先确认请求去了哪里](#1-一条值钱的排查心法先确认请求去了哪里)
- [2. 外部 IO 必须有超时兜底](#2-外部-io-必须有超时兜底)
- [3. 被测对象必须隔离状态](#3-被测对象必须隔离状态)
  - [3.1 沙箱换掉工作目录，相对路径的适配器直接 127](#31-沙箱换掉工作目录相对路径的适配器直接-127)
  - [3.2 沙箱 HOME 是空的，智能体拿不到自己的凭据](#32-沙箱-home-是空的智能体拿不到自己的凭据)
  - [3.3 被测对象自己生成了同名凭据目录，把注入的那份遮蔽掉](#33-被测对象自己生成了同名凭据目录把注入的那份遮蔽掉)
- [4. 写评测用例 ≠ 写自然语言对话](#4-写评测用例--写自然语言对话)
- [5. ACP 协议的三个具体坑](#5-acp-协议的三个具体坑)
- [6. 别被配置表象迷惑](#6-别被配置表象迷惑)
- [7. 性能：为什么一个用例要 90 秒](#7-性能为什么一个用例要-90-秒)
- [8. 工程习惯类](#8-工程习惯类)
- [9. 成本意识](#9-成本意识)
- [附录：关键命令速查](#附录关键命令速查)

---

## 1. 一条值钱的排查心法：先确认请求去了哪里

**现象**：给 Hermes 配 DashScope 时，报错在两个形态间反复横跳——
先是 `401 Missing Authentication header`，补齐 Key 后变成 `401 Incorrect API key provided`。
但**同一个 Key 用 curl 直连 DashScope 完全正常**。

**根因**：Hermes 压根没往我配置的 `base_url` 发请求，401 来自它内置的默认端点。

**定位方法（关键）**：起一个本地"回声服务"充当 base_url，看它收到什么：

```python
# 记录请求头与 body，返回一个合法的 OpenAI 响应
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length') or 0))
        open('/tmp/echo_headers.log','w').write(str(self.headers) + "\n---BODY---\n" + body.decode())
        # ...返回合法响应
ThreadingHTTPServer(('127.0.0.1', 8787), H).serve_forever()
```

结果：**一条请求都没收到**。真相立刻清楚，不再空转。

**教训**：当"配置明明写对了却不生效"时，**先用回声服务（或 tcpdump / 代理日志）确认请求究竟发去了哪里**，再决定怎么改。否则会在错误的方向上反复试配置，白白烧掉几个回合。

**可迁移到**：任何「第三方工具 + 自定义端点 / 代理 / 环境变量」的集成场景。

---

## 2. 外部 IO 必须有超时兜底

**现象**：评测跑到第 10 条用例时**挂死 19 分钟**，最后被强杀，那次的 token 全白烧。

**根因**：`BufferedReader.readLine()` 在对方不响应时会**无限阻塞**。
我当时写的超时检查只在"两次读取之间"生效——如果 Hermes 对某条请求不回任何东西，`readLine()` 就永远卡住，deadline 根本没机会被检查。

**处理**：后台读线程 + 队列超时取行。

```java
private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
// 后台线程持续 readLine 入队
// 取行时：
long remaining = deadline - System.currentTimeMillis();
String line = queue.poll(remaining, TimeUnit.MILLISECONDS);
if (line == null) break;   // 超时即退出，不再无限等待
```

**教训**：**评测工具任何外部 IO 都要有超时兜底。** 挂死的代价不只是等待——是整轮结果全丢。

---

## 3. 被测对象必须隔离状态

**现象**：如果所有用例共用同一个 `HERMES_HOME`，上一个用例记住的验证码会被下一个用例召回到，评测结果不可信。

**处理**：每个用例分配独立 `HERMES_HOME`，**并从主目录拷贝 `config.yaml` 与 `.env`**：

```java
Path home = root.resolve(sanitize(sessionId));
Files.createDirectories(home);
copyIfAbsent("config.yaml", home);   // 不拷配置会触发"首次运行"设置闸门
copyIfAbsent(".env", home);          // 拿不到回答，表现为全部"无响应"
```

这与 `scripts/kylinbot-adapter.sh` 用 `--config-dir` 隔离是同一个模式。
**隔离目录 ≠ 空目录，配置要跟着走**——这一点很容易漏，而且漏了的表现（全部无响应）会误导你去查鉴权。

### 3.1 沙箱换掉工作目录，相对路径的适配器直接 127

**现象**：KylinBot 冒烟测试 283 条**全部不可判**，总耗时 0.6 秒。证据里每条都是 `退出码=127`。

**根因**：`ProcessSandbox.apply()` 里有 `builder.directory(root)`——被测进程的工作目录被换成了沙箱根，
于是配置里的 `scripts/kylinbot-adapter.sh` 这个**相对路径**在沙箱里不存在，shell 报 127（command not found）。

**处理**：在 `CommandAnswerer` 渲染命令之前，把 `command` / `args` / `resetArgs` 里长得像相对路径的 token
按**启动 litmus 时的目录**锚成绝对路径（`anchor()`）。三条约束：

- 只锚"含 `/` 且不是绝对路径且不以 `-` 开头"的 token——靠 PATH 解析的程序名（`kylin-bot`）不能被改写；
- 锚完要 `Files.exists()` 校验，不存在就**原样保留**，让 shell 报它自己的错，不要吞掉；
- 必须在渲染**之前**做，否则提问文本里长得像路径的字串会被误改。

**教训**：给子进程换 cwd 是隔离的必要动作，但它会静默破坏所有相对路径引用。
**加隔离时要把"隐式依赖宿主 cwd"的东西一并显式化**，否则故障表现为"被测对象无响应"，
而真实原因是评测端自己把命令拼错了。

### 3.2 沙箱 HOME 是空的，智能体拿不到自己的凭据

**现象**：修完 3.1 后，只有 `case-r1` 通过，其余全部 `退出码=1`、耗时约 15ms。

**根因**：适配器里 `cp "$HOME/.kylinbot/config.toml"` 在 `set -euo pipefail` 下直接失败——
沙箱把 `HOME` 指向了一个**新建的空目录**。`r1` 之所以能过，是因为 `/tmp/litmus-kylinbot/case-r1/`
是之前手工运行留下的，命中了适配器的"已存在就跳过拷贝"分支。**这是最危险的一类假通过：
只有一条能过，看起来像"用例有问题"，实际是环境残留。**

**处理**：加通用的 `sandboxSeed` 配置项，由评测端把宿主上的凭据文件注入沙箱家目录：

```json
{ "type": "command", "command": "bash",
  "args": ["scripts/kylinbot-adapter.sh", "{sessionId}", "{question}"],
  "sandboxSeed": ["~/.kylinbot/config.toml", "~/.kylinbot/.secret_key"] }
```

注入项权限收到 `600`（owner-only），且 `ProcessSandbox.describe()` 会如实披露。

**这里还有一个我亲手写出来的二次缺陷**：`describe()` 最初只列**注入成功**的项。
我在容器里用一个写错路径的 seed 测，发现"源文件不存在"这件事在报告里**一个字都没有**——
接入者只会看到"智能体无响应"，查不到根因。修复是同时记录 `seedIssues`，输出
`注入未完成 N 项（… → 未找到，已跳过）`，并加测试断言这句话必须出现。

**教训**：**披露失败比披露成功更重要。** 只报成功项的自述等于把故障藏起来；
凡是"尽力而为、失败可跳过"的逻辑，失败路径必须进报告。

### 3.3 被测对象自己生成了同名凭据目录，把注入的那份遮蔽掉

**现象**：full 283 条跑到尾声时，`p28`–`p30`、`x1`–`x15`、`t17` 共 19 条变成「无响应」，
随后**对照臂 102 条全部空回答**。失败极快——适配器 `退出码=1、耗时 6–8ms`，
连 `kylin-bot` 都没启动。主臂前 264 条完全正常。

**根因**：适配器选凭据源目录的逻辑是「`$HOME/.kylinbot` 里有 `config.toml` 就用它，否则用 `$HOME`」。
而被测对象自己会在运行中创建 `$HOME/.kylinbot/`，并在里面**生成一份默认 `config.toml`**
（沙箱家目录里那份的 mtime 就是故障开始的那一刻，旁边还留着一个 `config.toml.bak-*`）。
它**不含 `.secret_key`**。于是从那一刻起，每个**新会话目录**都命中了第一档、
拷 `config.toml` 成功、拷 `.secret_key` 失败，`set -euo pipefail` 直接退出 1。

三个放大效应叠在一起，才让它看起来像"额度用完了"：

1. **只影响新目录**：已经建好的会话目录走"跳过"分支，照旧能跑——所以故障是**渐进**的，
   前 264 条没事，最后 19 条全灭；
2. **对照臂全是新目录**（会话 id 带 `-control` 后缀），所以是 102/102 整臂失败；
3. **报告只写「无响应」**：`NO_RESPONSE` 归因把"接入导管坏了"和"被测答不出"混在同一个标签里，
   除非去翻 `artifacts/*.md` 里的退出码与耗时，否则看不出来。

**处理**（`scripts/kylinbot-adapter.sh`）：

```bash
# 必须成对才算候选目录，单看 config.toml 会被被测对象自己生成的那份骗掉
for cand in "$HOME/.kylinbot" "$HOME"; do
    if [ -f "$cand/config.toml" ] && [ -f "$cand/.secret_key" ]; then seed="$cand"; break; fi
done
[ -n "$seed" ] || { echo "kylinbot-adapter: 找不到成对凭据…已尝试 …" >&2; exit 3; }
# 两份都拷成功才一起落定：不留「有 config.toml、没 .secret_key」的半成品目录
```

判据从「有没有 config.toml」改成「**两份凭据是否成对**」，失败时 `exit 3` 并在 stderr 写清试过哪些路径。

**教训**：
- **隔离目录里不要放"看起来像配置"的东西**：被测进程会往里写自己的默认配置，
  于是同一目录下同时存在「注入的真凭据」和「自动生成的假配置」，
  按存在性挑选的探测逻辑一定会挑错。要么按**内容完整性**（成对、可解密）判，
  要么把注入项放到被测对象不会写的路径上。
- 「一半成功」的状态比失败更危险：`config.toml` 拷完、`.secret_key` 没拷到，
  目录就永久处于"看起来已就绪"的状态。先拷到临时目录、两份都成功再一起落位。
- 一次长跑结束先看**覆盖率与不可判分布**再看分数：这一轮覆盖率 92.9%（目标 ≥97%）、
  不可判集中在尾部连续区段并且耗时都是 8ms，这两点合起来就是"导管坏了"的信号，
  此时 60.5% 这个有效分**不可发布**，应当整轮重跑而不是就地解释。


---

## 4. 写评测用例 ≠ 写自然语言对话

**现象**：数据集扩到 150 条时，3 条新用例（`c15` `t13` `t17`）参考智能体**全部判失败**。

**根因**：**提问与要召回的记忆没有任何共同的检索词**，`recall()` 的 `score > 0` 过滤直接把它们排除了。
例：注入"用户要求所有金额保留两位小数"，却问"帮我算一下这笔费用"——两句话字面无交集，召回落空。

**处理**：让提问带上目标记忆里的特征词（改成"这笔费用要保留几位**小数**？"）。

**教训**：
1. **每条新用例都要实跑验证**，不能只靠读代码觉得没问题——我这三条就是"看起来很自然"但实际召不回。
2. `RecallAnswerer` 会把 top-5 召回结果全部拼接输出，所以**干扰项只要与提问共享检索词就会被一起说出来**。
   这正是相近区分维度措辞必须严格设计的原因，也是"同一时刻的互斥表述"不适合用规则判定的原因（会必然误判）。

---

## 5. ACP 协议的三个具体坑

ACP（Agent Communication Protocol）是 Hermes 面向编辑器暴露的 JSON-RPC over stdio 接口，是驱动真实 Hermes 智能体的正确入口。

### 5.1 坑 1：`session/new` 的 `mcpServers` 是必填

只给 `cwd` 会报 `-32602 Invalid params`（Pydantic 校验）。不接外部工具时**传空数组**即可：

```java
mapper.createObjectNode().put("cwd", cwd).set("mcpServers", mapper.createArrayNode())
```

### 5.2 坑 2：响应里混着"思考过程"

ACP 会把 `agent_thought_chunk` 也流式推过来——那是模型的**内心独白，不是回答**。
我第一版把它当答案，抽出来的是 "Let me parse this. The user..."。

处理：只取含 `message` 的块（`agent_message_chunk`），跳过 `thought` 与工具调用。

### 5.3 坑 3：`readLine()` 无限阻塞

见[第二节](#二外部-io-必须有超时兜底)。

---

## 6. 别被配置表象迷惑

**现象**：`kylin-agent-runtime status` 显示 `Provider: Custom endpoint`，看起来配置生效了；但请求仍 401。

**根因**：状态显示的是"配置已读到"，不代表"请求会发到那里"。二者是两件事。

**教训**：**"配置被识别"≠"配置被使用"。** 别用 status/doctor 的输出当作端到端验证，真正的验证只有一种——看到真实回答。

顺带一个同类坑：`.env` 里写着 `LLM_MODEL` 的模板，但注释明确说**该变量已不再从 .env 读取**，默认模型只能改 `config.yaml` 的 `model.default`。照着模板填是无效的。

---

## 7. 性能：为什么一个用例要 90 秒

实测拆解（**零 token** 即可测量）：

| 阶段 | 耗时 |
|---|---|
| 启动 `hermes-acp` 进程 + `initialize` | **0.5 秒** |
| 其余（真正提问） | 约 90 秒 |

所以**慢不在进程启动，全在智能体循环**。原因：

1. **每轮请求都带上 17 个工具定义**（日志里的 `Tools: 17`），payload 很大；
2. **多轮往返**：思考 → 可能的工具调用 → 再思考 → 才给出最终回答，一次提问对应多次模型调用；
3. 默认还会流式输出思考过程。

### 7.1 但两个"想当然"的优化都被实测证伪了

| 尝试 | 实测单用例耗时 |
|---|---|
| 基线 | ~90 秒 |
| 关闭工具集（`disabled_toolsets: ["hermes-acp"]` + `max_turns: 5`） | **100 秒**（没变快） |
| 再关推理（`reasoning_effort: "none"`） | **87 秒**（仍是噪声范围） |

也就是说：**这 90 秒不是工具 payload，也不是模型思考，而是 Hermes 每个回合固有的处理开销**（很可能是回答后还有一次记忆抽取/整理类的额外模型调用——而记忆恰恰是我们要测的东西，不能关）。

**结论：接受 ~90 秒/用例，别再往这个方向优化。**

不过上面两个设置**仍然建议保留**——它们虽然没省时间，但能**省 token**（少掉每轮 17 个工具定义的输入 token、少掉思考过程的输出 token）。评测批量跑时这是实打实的省钱。

**教训**：
1. 性能问题先**分段测量**再归因——这次如果没测那 0.5 秒，很容易误判成"起进程太慢"。
2. **优化必须实测验证，不能靠推理**。我基于"17 个工具 → payload 大"和"思考模式 → 输出长"这两个看起来很合理的推断做了两次改动，结果都没效果。
3. 省时间 ≠ 省 token，两者要分开衡量。

---

## 8. 工程习惯类

### 8.1 别提交已知有缺陷的代码
bash 版 `hermes-adapter.sh` 有 `session not found` 缺陷（`--resume` 在首次调用会失败）且已被 ACP 方案取代，提交前直接删除。宁可少一个文件，不留坏代码。

### 8.2 API Key 不写进对话输出
需要落盘时用脚本读环境变量写入，校验时脱敏；方案失败后**主动从磁盘清除 Key 并恢复默认配置**。

### 8.3 服务端注入的字段要用 `WRITE_ONLY`
`AnalyzeResponse.dataGuard` 加了 `@JsonProperty(access = WRITE_ONLY)`。
否则 `BeanOutputConverter` 会把字段塞进 schema 让模型去"生成"它——既污染提示词，又可能因格式不符导致整段解析失败。

### 8.4 改硬编码断言要同步
数据集 72 → 150 时，测试里 5 处 `72` / `12` 全都要改，漏一处就红。同理，新增的"反向用例"会改变失忆回答器的通过数断言（2 条 → 3 条）。

### 8.5 删除零调用的死代码
引用计数扫一遍即可发现。注意区分假阳性：Spring 的 `@Configuration` / 启动类**零显式引用但由框架装配**，不是死代码。

### 8.6 「日志全是问号，产物却是好的」——那不是编码写错了，是控制台编码

**现象**：在没有 `LANG` 的环境（`env -i`、CI 容器、最小化信创服务器）里跑 CLI，屏幕上是这样：

```
???: ?? pressure = pressure(30)?? 30 ?
```

但同一次运行落盘的 `report.txt` 中文完全正常。

**根因**：落盘一律显式 `StandardCharsets.UTF_8`，而 `System.out` 用的是 JVM 的控制台编码；
POSIX/C locale 下它是 `ANSI_X3.4-1968`（ASCII），编不出中文就替换成 `?`。**两条路径的编码来源不同**，
所以一半好一半坏——这种"自相矛盾"的现象最容易把人引去查落盘代码，而落盘代码没问题。

**处理**：`Cli.main` 开头判断一次，只在**当前编码连「测」都编不出来**时把 `System.out/err` 换成 UTF-8：

```java
if (consoleCharset().newEncoder().canEncode('测')) return;   // GBK 编得出，不动它
System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8));
```

判据用「能不能编出这个字」而不是「是不是 Windows / 是不是 UTF-8」，因为**无条件换成 UTF-8 会在
Windows 的 GBK 控制台上制造真乱码**（字节流与控制台代码页不符）——那样就从"退化"变成"损坏"了。
编码属性要按 `stdout.encoding` → `sun.stdout.encoding` → `native.encoding` → `file.encoding` 依次问，
Java 17 只有后三个，只问 `stdout.encoding` 等于在 17 上不生效。

**教训**：诊断信息本身的编码也是可观测性的一部分。**"产物对、终端错"要当成一条独立缺陷记下来**，
否则用户装到没有 locale 的机器上，第一眼看的就是满屏问号，而他不会去翻 `report.txt`。

### 8.7 打包脚本里两处"跑通了也是错的"

`scripts/build-deb.sh` 曾经能产出 deb，但产出的是错的：

1. **jar 名写死成 `memory-bench-1.0.0-SNAPSHOT.jar`**，而 pom 里已经是 `1.0.0`——版本号在两处各写一份，
   迟早对不上。改成从 `pom.xml` 读一次，脚本里不再出现第二份版本号。
2. **`jpackage --input target`**：jpackage 会把 input 目录**整个**拷进 `/opt/<app>/lib/`，
   于是 `classes/`、`test-classes/`、`surefire-reports/` 全被发出去了。
   包能装、能跑，体积多几兆，没有任何报错——**这类缺陷只有 `dpkg-deb -c` 才看得见**。
   改成先把可执行 jar 单独暂存到一个临时目录，再拿那个目录当 input。

顺带把 `gitCommit` 也修正了：工作区脏时标成 `<hash>-dirty`。
否则发出去的 deb 声称自己出自某个提交，而那个提交里根本没有这些改动——
"这批数字出自哪个版本"就答错了，而且错得看不出来。

**教训**：构建脚本的验证不能只看退出码。**产物要拆开看内容**（`dpkg-deb -c` / `unzip -l`），
身份要能自证（版本号、提交号只从一个来源读，脏就标脏）。

### 8.8 两处让「同输入同输出」悄悄失效的写法

这两处都不报错、不抛异常、单跑一次结果也完全正确，只有**跑两次并逐字节比**才会暴露。
它们破坏的正是这个工具最核心的卖点（内置被测 σ=0、跨 JVM `report.txt` sha256 一致）。

**(1) `Map.copyOf` 的迭代次序每次 JVM 启动都不一样**

```java
public record Result(..., Map<FailureCause, int[]> byCause) {
    public Result {
        byCause = Map.copyOf(byCause);   // ← 报告按这个 Map 逐行打印归因分类
    }
}
```

`java.util.ImmutableCollections` 内部有一个 **`SALT`，每个 JVM 进程启动时随机生成**，
用来打散哈希表的迭代次序（防 HashDoS）。所以 `Map.copyOf` / `Set.copyOf` / `Map.of(k,v,...)`
的迭代次序在**同一个 jar、同一个 JVM 版本、两次运行之间**就可能不同。
只要这个次序流进了任何落盘产物，"逐字节一致"就没了。

改法：要不可变又要保序，就写 `Collections.unmodifiableMap(new LinkedHashMap<>(byCause))`。
`List.copyOf` 没这个问题——List 的次序是契约的一部分。

**(2) 用 `System.currentTimeMillis()` 当排序键，等于把可复现性交给了时钟**

召回排序原本是「相关度倒序，同分再按 `updatedAt` 倒序」，然后 `limit(topK)`。
一次注入十几条事实时，这些条目**大概率落在同一个毫秒里，但也可能跨毫秒边界**——
跨不跨纯看机器当时快不快。同分 + 同时间戳时稳定排序退回写入次序，
同分 + 不同时间戳时倒序生效，两种情况截断掉的内容不一样。

表现是：`p5`（长期保持，期望命中「月结 30 天」）在两次完全相同的运行里一次通过一次未过，
`reference` 的总体从 100.0% 变成 99.6%。**单跑 pressure 档时 4/4 都一致**——
用例少、时间紧，撞不上毫秒边界，所以这个缺陷在小样本上完全隐形。

改法：同分改用**写入次序**（在会话记忆文件里的行号）倒序。`write` 追加、`update` 把替换条目
追加到末尾，所以行号天然就是时间次序——语义没变，但不再读时钟。

**教训**：
- 排序键里不许出现时钟、随机数、`UUID`、`identityHashCode`、`Files.list` 的目录次序。
  需要"新的优先"时，用**结构上等价但确定**的量（写入序号）替代时间戳。
- 「跑一次对了」不叫可复现。**可复现性只能由"跑两次比 sha256"证明**，
  而且要在最大题集上比——这次两个缺陷都只在 full 283 上暴露，basic 150 一直是对的，
  所以此前"σ=0"的结论其实是在小样本上得出的。
- 这次是**门禁抓到的，不是人抓到的**：`scripts/verify-container.sh` 里那条
  「JDK 17 与 JDK 21 的 report.txt sha256 必须相同」直接红了。
  顺带还暴露出该脚本自己的一个 bug——它在容器里就把输出 `| grep -E "覆盖率"` 过滤掉了，
  导致后面对同一份日志断言归因证据化时永远失败，报错信息却长得像被测对象坏了。
  **断言用的日志不要提前过滤。**

---

### 8.9 一条画图命令把一次运行的对账能力弄坏了（`rerender` 改写源目录）

`rerender --from <dir>` 的用途是「图不好看，重出一次，不重跑评测」。它的实现里
`--from` 既是读的地方，也是写的地方：`radar.svg` 和 `report.html` 内嵌的那段 SVG
直接落回源目录。

问题在于这两个文件**在 `manifest.json` 的产物哈希清单里**。于是：

```bash
litmus rerender --from out/kylinbot-full      # 只想换张图
litmus verify  --from out/kylinbot-full       # ✗ 产物被改动: radar.svg / report.html
```

**没有任何人改动过那次评测的数据**，但对账从此报不一致——而这恰恰是本项目对外承诺的第一条
（「报告里的每个数都能 verify 回去」）。写命令的人只会认为工具坏了。

根因不是"忘了考虑 `--out`"。协议里**早就写了这一条**——`docs/SPEC-EVIDENCE.md` §1
「**任何工具都不得回写源目录**……回写会让对账全部失配，等于为了加工而毁掉原始证据」。
`regrade` 与 `compare` 都守住了，`rerender` 是同一类命令里漏掉的那一个，而且漏了整整一个版本。
**写在规范里的不变量，如果不落在每一条读同一目录的命令上，就等于没写。**
凡是「读一份已有产物、再写一份产物」的子命令，都要过这条检查。

改法（三条，都由测试钉住）：

- `--out` 生效：产物写到新目录，源目录一个字节都不动，并在结束时明说
  「源目录未被改动」；
- 不给 `--out` 时，**目录里有 `manifest.json` 就直接拒绝**，报错写清楚为什么拒绝、
  该加哪个参数——临时目录（没有哈希可护）才允许就地改写；
- 顺手修掉一处误导：原来的「未在 report.html 中找到可替换的 SVG 块」有两种触发路径，
  「HTML 里确实没有 `<svg>` 块」和「替换后内容与原文逐字节相同」被合并成了同一句话，
  第二次运行（内容本来就一致）会报出一句假话。现在分开：没块说没块，一致说一致。

**教训**：交付物里每一条"轻量工具命令"都要问一遍——它会不会写回别人的证据？
`verify` 只保护产物不被无声篡改，它保护不了我们自己写的辅助子命令。

### 8.10 `.gitignore` 裸写 `evidence/`，把源码包排除在提交之外

运行产物目录叫 `evidence/`，于是忽略规则写成裸 `evidence/`。它匹配**任何深度**的同名目录，
包括 `src/main/java/io/github/agentlitmus/evidence/`——那是代码包。后果不是"少个文件"，
而是**仓库对外是一个整体、对内缺一块**：

- 包里最早的两个文件已经在 index 里，gitignore 管不到已跟踪文件，于是它们照常提交、
  照常编译，**看不出任何异常**；
- 后加的两个文件从未入库，而 `RunManifest`、两个 Runner、契约测试都 import 它们，
  本地工作树完整所以本地全绿，**任何 fresh clone（含 CI）直接编译失败**。

**教训**：忽略规则必须锚定（`/evidence/`），或在旁边留一条显式豁免（`!**/src/**/evidence/`）。
更硬的判据是：**"本地测试全绿"不能证明"仓库完整"**——能证明它的只有一次从 `git clone`
出来的干净目录里跑出来的构建。

### 8.11 清洗文件名把两份证据洗成了同一个名字

会话 ID → 文件名要做字符清洗（冒号在 Windows 上非法），于是所有不安全字符折叠成 `-`。
折叠是**多对一**的：`case-报销规则` 与 `case-报销流程` 都变成 `case.md`，两次用例的产物
互相覆盖。危险的地方在于它**不留痕迹**——`manifest.json` 按文件名登记哈希，最后只剩一个文件、
一个哈希，对账完全一致，只有证据少了一半。注释还写着"折叠规则是确定性的，不受影响"：
确定性（同输入同输出）成立，单射性（不同输入不同输出）没人检查过。

**教训**：任何"把标识符变成文件名"的映射，都要问一遍**它是不是单射**。
不保证单射就必须加消歧后缀（这里用会话 ID 的哈希前 8 位，仍然确定、仍然可复现）。
凡是"多个来源落盘到同一个位置"的代码，覆盖方向本身就是静默数据丢失的通道。

---

## 9. 成本意识

**编译、跑离线确定性测试 = 0 token；只有真正让模型跑用例才花钱。**

验证顺序应该是：

```
编译（0） → 离线确定性测试（0） → 最少用例验证端到端（1 条即可）
```

不要用大样本去"试试看"。这次用**单条用例**就确认了 hang 修复生效。

正式评测的采样建议（兼顾可信度与成本）：

| 被测对象 | 建议 | 理由 |
|---|---|---|
| 内置确定性智能体（reference / degraded） | **全量 `--suite full`（283）** | 0 token、秒级，是"尺子本身有效"的完整证据 |
| 真实智能体（Hermes / KylinBot） | **分层抽样**（如每维 5 条 = 30 条，须含跨会话用例），并明确标注"抽样 N 条" | 慢且烧 token；抽样足够证明"能评真实智能体" |
| 稳定性（`--repeat`） | 小样本即可 | 真实智能体非确定性，波动本身就是要展示的证据 |

**红线**：绝不能把抽样分数当成全量分数呈现。外部使用者对"选择性取样"的不信任，远大于对"诚实的较小样本"的质疑。

---

## 附录：关键命令速查

```bash
# 零 token 验证：编译 + 离线测试
./mvnw -o clean package -DskipTests
./mvnw -o test                      # 198 个离线确定性测试（24 个测试类）

# 只跑内置智能体（0 token，秒级）
java -jar target/memory-bench-1.0.0.jar run --agents reference,degraded --out out/

# 跑真实智能体（烧 token，先小样本）
java -jar target/memory-bench-1.0.0.jar run \
     --agents hermes --cases small.json --out probe/

# 导出/裁剪小样本
java -jar target/memory-bench-1.0.0.jar export-cases --out out/
# 再用脚本按维度分层抽取

# 校验是否真接上（比看分数可靠）
python3 -c "
import json
rows=[json.loads(l) for l in open('probe/evidence/hermes.jsonl',encoding='utf-8')]
print('证据条数:',len(rows),'空回答:',sum(1 for r in rows if not r['content'].strip()))"
```

**判据**：证据有内容且**空回答为 0**，才证明真连通；否则分数再好看也是假的。
