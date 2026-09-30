# 踩坑经验总结

> 本文记录用 memory-bench 评测**真实智能体**（openKylin 生态的 Hermes Agent）全过程踩到的坑。
> 每条都按「现象 → 根因 → 处理 → 可迁移的教训」组织，便于复用。

## 目录

- [一、一条值钱的排查心法：先确认请求去了哪里](#一一条值钱的排查心法先确认请求去了哪里)
- [二、外部 IO 必须有超时兜底](#二外部-io-必须有超时兜底)
- [三、被测对象必须隔离状态](#三被测对象必须隔离状态)
- [四、写评测用例 ≠ 写自然语言对话](#四写评测用例--写自然语言对话)
- [五、ACP 协议的三个具体坑](#五acp-协议的三个具体坑)
- [六、别被配置表象迷惑](#六别被配置表象迷惑)
- [七、性能：为什么一个用例要 90 秒](#七性能为什么一个用例要-90-秒)
- [八、工程习惯类](#八工程习惯类)
- [九、成本意识](#九成本意识)
- [附录：关键命令速查](#附录关键命令速查)

---

## 一、一条值钱的排查心法：先确认请求去了哪里

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

## 二、外部 IO 必须有超时兜底

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

## 三、被测对象必须隔离状态

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

---

## 四、写评测用例 ≠ 写自然语言对话

**现象**：数据集扩到 150 条时，3 条新用例（`c15` `t13` `t17`）参考智能体**全部判失败**。

**根因**：**提问与要召回的记忆没有任何共同的检索词**，`recall()` 的 `score > 0` 过滤直接把它们排除了。
例：注入"用户要求所有金额保留两位小数"，却问"帮我算一下这笔费用"——两句话字面无交集，召回落空。

**处理**：让提问带上目标记忆里的特征词（改成"这笔费用要保留几位**小数**？"）。

**教训**：
1. **每条新用例都要实跑验证**，不能只靠读代码觉得没问题——我这三条就是"看起来很自然"但实际召不回。
2. `RecallAnswerer` 会把 top-5 召回结果全部拼接输出，所以**干扰项只要与提问共享检索词就会被一起说出来**。
   这正是相近区分维度措辞必须严格设计的原因，也是"同一时刻的互斥表述"不适合用规则判定的原因（会必然误判）。

---

## 五、ACP 协议的三个具体坑

ACP（Agent Communication Protocol）是 Hermes 面向编辑器暴露的 JSON-RPC over stdio 接口，是驱动真实 Hermes 智能体的正确入口。

### 坑 1：`session/new` 的 `mcpServers` 是必填

只给 `cwd` 会报 `-32602 Invalid params`（Pydantic 校验）。不接外部工具时**传空数组**即可：

```java
mapper.createObjectNode().put("cwd", cwd).set("mcpServers", mapper.createArrayNode())
```

### 坑 2：响应里混着"思考过程"

ACP 会把 `agent_thought_chunk` 也流式推过来——那是模型的**内心独白，不是回答**。
我第一版把它当答案，抽出来的是 "Let me parse this. The user..."。

处理：只取含 `message` 的块（`agent_message_chunk`），跳过 `thought` 与工具调用。

### 坑 3：`readLine()` 无限阻塞

见[第二节](#二外部-io-必须有超时兜底)。

---

## 六、别被配置表象迷惑

**现象**：`kylin-agent-runtime status` 显示 `Provider: Custom endpoint`，看起来配置生效了；但请求仍 401。

**根因**：状态显示的是"配置已读到"，不代表"请求会发到那里"。二者是两件事。

**教训**：**"配置被识别"≠"配置被使用"。** 别用 status/doctor 的输出当作端到端验证，真正的验证只有一种——看到真实回答。

顺带一个同类坑：`.env` 里写着 `LLM_MODEL` 的模板，但注释明确说**该变量已不再从 .env 读取**，默认模型只能改 `config.yaml` 的 `model.default`。照着模板填是无效的。

---

## 七、性能：为什么一个用例要 90 秒

实测拆解（**零 token** 即可测量）：

| 阶段 | 耗时 |
|---|---|
| 启动 `hermes-acp` 进程 + `initialize` | **0.5 秒** |
| 其余（真正提问） | 约 90 秒 |

所以**慢不在进程启动，全在智能体循环**。原因：

1. **每轮请求都带上 17 个工具定义**（日志里的 `Tools: 17`），payload 很大；
2. **多轮往返**：思考 → 可能的工具调用 → 再思考 → 才给出最终回答，一次提问对应多次模型调用；
3. 默认还会流式输出思考过程。

### 但两个"想当然"的优化都被实测证伪了

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

## 八、工程习惯类

### 别提交已知有缺陷的代码
bash 版 `hermes-adapter.sh` 有 `session not found` 缺陷（`--resume` 在首次调用会失败）且已被 ACP 方案取代，提交前直接删除。宁可少一个文件，不留坏代码。

### API Key 不写进对话输出
需要落盘时用脚本读环境变量写入，校验时脱敏；方案失败后**主动从磁盘清除 Key 并恢复默认配置**。

### 服务端注入的字段要用 `WRITE_ONLY`
`AnalyzeResponse.dataGuard` 加了 `@JsonProperty(access = WRITE_ONLY)`。
否则 `BeanOutputConverter` 会把字段塞进 schema 让模型去"生成"它——既污染提示词，又可能因格式不符导致整段解析失败。

### 改硬编码断言要同步
数据集 72 → 150 时，测试里 5 处 `72` / `12` 全都要改，漏一处就红。同理，新增的"反向用例"会改变失忆回答器的通过数断言（2 条 → 3 条）。

### 删除零调用的死代码
引用计数扫一遍即可发现。注意区分假阳性：Spring 的 `@Configuration` / 启动类**零显式引用但由框架装配**，不是死代码。

---

## 九、成本意识

**编译、跑离线确定性测试 = 0 token；只有真正让模型跑用例才花钱。**

验证顺序应该是：

```
编译（0） → 离线确定性测试（0） → 最少用例验证端到端（1 条即可）
```

不要用大样本去"试试看"。这次用**单条用例**就确认了 hang 修复生效。

正式评测的采样建议（兼顾可信度与成本）：

| 被测对象 | 建议 | 理由 |
|---|---|---|
| 内置确定性智能体（reference / degraded） | **全量 150** | 0 token、秒级，是"尺子本身有效"的完整证据 |
| 真实智能体（Hermes / KylinBot） | **分层抽样**（如每维 5 条 = 30 条，须含跨会话用例），并明确标注"抽样 N 条" | 慢且烧 token；抽样足够证明"能评真实智能体" |
| 稳定性（`--repeat`） | 小样本即可 | 真实智能体非确定性，波动本身就是要展示的证据 |

**红线**：绝不能把抽样分数当成全量分数呈现。评审对"选择性取样"的惩罚，远大于对"诚实的较小样本"的扣分。

---

## 附录：关键命令速查

```bash
# 零 token 验证：编译 + 离线测试
./mvnw -o clean package -DskipTests
./mvnw -o test                      # 30 个离线确定性测试

# 只跑内置智能体（0 token，秒级）
java -jar target/memory-bench-0.1.0-SNAPSHOT.jar run --agents reference,degraded --out out/

# 跑真实智能体（烧 token，先小样本）
java -jar target/memory-bench-0.1.0-SNAPSHOT.jar run \
     --agents hermes --cases small.json --out probe/

# 导出/裁剪小样本
java -jar target/memory-bench-0.1.0-SNAPSHOT.jar export-cases --out out/
# 再用脚本按维度分层抽取

# 校验是否真接上（比看分数可靠）
python3 -c "
import json
rows=[json.loads(l) for l in open('probe/evidence/hermes.jsonl',encoding='utf-8')]
print('证据条数:',len(rows),'空回答:',sum(1 for r in rows if not r['content'].strip()))"
```

**判据**：证据有内容且**空回答为 0**，才证明真连通；否则分数再好看也是假的。
