# 用例 u2（动态更新）

## 注入记忆
- 用户使用的模型是 qwen-plus（retainable=true）

## 更新记忆
- 用户使用的模型是 qwen-max

## 提问
- 我现在用哪个模型？

## 智能体回答
用户使用的模型是 qwen-plus

## 判定
- Judgment[caseId=u2, dimension=UPDATE, passed=false, score=0.0, reason=包含[qwen-max] 未命中；排除[qwen-plus] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
