# agentlitmus（pip 入口）

**AgentLitmus 智能体长期记忆评测基准**的 Python 入口——免装 Java。

```bash
pip install agentlitmus
agentlitmus run --out result/        # 内置两款对照智能体，离线可跑
agentlitmus selftest                 # 尺子自证（零密钥）
agentlitmus verify --from result/    # 运行契约对账
```

首次执行时自动从 GitHub Releases 下载当前平台的自包含可执行
（内置精简运行时，macOS arm64/x64、Linux arm64/x64、Windows x64），
之后离线可用，命令与参数原样转发。

## 工作方式（以及为什么不做一个纯 Python 实现）

判定器保持单一实现（Java）：同一批证据在任何环境必须判出同一结果，
这是 AgentLitmus 可信链（manifest 对账 / 离线重评 / 跨运行回归比较）的前提。
本包只解决「不想装 Java」这一件事，不复刻任何评测逻辑。

## 环境变量

| 变量 | 作用 |
|---|---|
| `AGENTLITMUS_BINARY` | 直接使用已安装的可执行（跳过下载），适合已有部署 |
| `AGENTLITMUS_VERSION` | 固定工具版本（默认与包版本一致） |
| `AGENTLITMUS_HOME` | 缓存目录（默认 `~/.agentlitmus`） |
| `AGENTLITMUS_DOWNLOAD_BASE` | 下载基址（默认 GitHub Releases；可指向镜像，支持 `file://`） |
| `AGENTLITMUS_NO_VERIFY` | 设为任意非空值跳过 sha256 校验（不建议） |

下载会按 Release 附带的 `checksums.txt` 做 sha256 校验；拿不到校验文件时
如实警告并继续，不静默跳过。

Apache License 2.0 · 上游仓库：<https://github.com/agent-litmus/memory-bench>
