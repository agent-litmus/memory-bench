# 用例 u5（动态更新）

## 注入记忆
- 用户当前的城市是杭州（retainable=true）

## 更新记忆
- 用户当前的城市是深圳

## 提问
- 我现在住在哪个城市？

## 智能体回答
用户当前的城市是深圳

## 判定
- Judgment[caseId=u5, dimension=UPDATE, passed=true, score=1.0, reason=包含[深圳] 命中；排除[杭州] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
