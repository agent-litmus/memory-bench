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
你现在住在**深圳**（Shenzhen）。

这条信息是我之前在长期记忆里存下来的（key: `user_city`）。如果搬家了或者记错了，告诉我一声，我帮你更新。

## 判定
- Judgment[caseId=u5, dimension=UPDATE, passed=true, score=1.0, reason=包含[深圳] 命中；排除[杭州] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
