# 用例 r5（长期保持） ｜ 跨会话

## 注入事实
- 用户的工位编号是 A-17

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我的工位编号是多少？

## 智能体回答
你的工位编号是 **A-17**。

## 判定
- Judgment[caseId=r5, dimension=RETENTION, passed=true, score=1.0, reason=包含[A-17] 命中, mode=rule, outcome=CORRECT, cause=null]
