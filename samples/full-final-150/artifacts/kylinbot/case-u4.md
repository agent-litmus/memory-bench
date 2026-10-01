# 用例 u4（动态更新）

## 注入事实
- 用户当前的城市是杭州

## 更新事实
- 用户当前的城市是深圳

## 提问
- 我现在住在哪个城市？

## 智能体回答
你现在住在**深圳**。

## 判定
- Judgment[caseId=u4, dimension=UPDATE, passed=true, score=1.0, reason=包含[深圳] 命中；排除[杭州] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
