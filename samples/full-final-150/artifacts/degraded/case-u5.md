# 用例 u5（动态更新）

## 注入记忆
- 用户当前的城市是杭州（retainable=true）

## 更新记忆
- 用户当前的城市是深圳

## 提问
- 我现在住在哪个城市？

## 智能体回答
用户当前的城市是杭州

## 判定
- Judgment[caseId=u5, dimension=UPDATE, passed=false, score=0.0, reason=包含[深圳] 未命中；排除[杭州] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
