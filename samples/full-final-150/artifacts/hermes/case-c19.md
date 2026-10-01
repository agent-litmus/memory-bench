# 用例 c19（记忆调用）

## 注入事实
- 用户不使用任何闭源大模型

## 提问
- 推荐一个模型给我

## 智能体回答
好的——您之前说过**不使用任何闭源大模型**，所以我只在开源 / 开放权重的模型里推荐，闭源的（GPT、Claude 等）直接排除。

**首选：DeepSeek-V3 系列**（如 DeepSeek-V3.2）
- 开放权重、MIT 许可，可自由商用和本地部署-代码和通用能力强，适合您这种 Java/工具链开发场景-官方 API 价格低，也可以用 vLLM/SGLang 自部署**按场景的备选：**

|需求 | 推荐 |
|---|---|
| 写代码为主 | Qwen3-Coder（开源，长上下文，仓库级补全强） |
|深度推理 | DeepSeek-R1（开源推理模型） |
| 本地/内网部署 | Qwen3-14B/32B 或 GLM-4.5-Air，单卡即可跑 |
|轻量端侧 | Qwen3-4B / GLM 小尺寸版 |

如果告诉我主要用途（写代码、跑 Agent、还是本地部署）和硬件条件，我可以帮您把范围收窄到一个具体型号和部署方式。

## 判定
- Judgment[caseId=c19, dimension=RECALL, passed=true, score=1.0, reason=包含[闭源] 命中, mode=rule, outcome=CORRECT, cause=null]
