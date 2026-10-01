# 用例 u2（动态更新）

## 注入事实
- 用户使用的模型是 qwen-plus

## 更新事实
- 用户使用的模型是 qwen-max

## 提问
- 我现在用哪个模型？

## 智能体回答
你当前使用的模型是 **deepseek-flash**（运行时显示：Model: deepseek-flash），当前没有待生效的模型切换（pending_switch 为空）。

如果你想换模型，我可以直接帮你切换，例如：
- `anthropic` / `claude-sonnet-4-6`
- `openai` / `gpt-4o`
- `deepseek` 系列其他版本

告诉我目标模型即可。

## 判定
- Judgment[caseId=u2, dimension=UPDATE, passed=false, score=0.0, reason=包含[qwen-max] 未命中；排除[qwen-plus] 未出现(合格), mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
