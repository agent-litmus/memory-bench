# 用例 u5（动态更新） ｜ 跨会话

## 注入事实
- 用户当前的城市是杭州

## 更新事实
- 用户当前的城市是深圳

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我现在住在哪个城市？

## 智能体回答
（空回答）

## 判定
- Judgment[caseId=u5, dimension=UPDATE, passed=false, score=0.0, reason=包含[深圳] 未命中；排除[杭州] 未出现(合格), mode=rule, outcome=OMISSION, cause=NO_RESPONSE]
