# 用例 c19（记忆调用）

## 注入事实
- 用户不使用任何闭源大模型

## 提问
- 推荐一个模型给我

## 智能体回答
你现在用的是 `deepseek/deepseek-flash`（偏快、便宜，适合日常对话和简单任务）。怎么选主要看你拿来干什么，按场景给你几个方向：

| 用途 | 推荐 | 说明 |
|---|---|---|
| 写代码 / 复杂工程 | `anthropic/claude-sonnet-4-6` 或 `openai/gpt-5` 类 | 工具调用、长上下文、代码能力最稳 |
| 深度推理 / 数学 | `deepseek/deepseek-reasoner`、`openai/o3` 系列 | 慢一点但推导更扎实 |
| 长文档 / 大仓库 | `gemini/gemini-2.5-pro` | 上下文窗口大，性价比高 |
| 国内直连、中文场景 | `glm/glm-4.6`、`qwen/qwen-max`、`moonshot/kimi-k2` | 无需代理、中文表现好 |
| 极致速度 / 低成本 | `groq` 或 `cerebras` 上的开源模型 | 响应极快，适合批量任务 |
| 完全本地 / 隐私 | `ollama`、`lmstudio`、`vllm` | 数据不出机器 |

如果你告诉我具体需求（比如「主要写 Python 代码，预算低」或「要处理 100 页 PDF」），我可以直接帮你切过去：

```
把当前模型切到 claude-sonnet-4-6
```

说一声就行。

## 判定
- Judgment[caseId=c19, dimension=RECALL, passed=false, score=0.0, reason=包含[闭源] 未命中, mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
