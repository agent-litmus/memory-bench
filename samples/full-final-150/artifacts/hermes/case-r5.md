# 用例 r5（长期保持） ｜ 跨会话

## 注入事实
- 用户的工位编号是 A-17

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我的工位编号是多少？

## 智能体回答
您的工位编号是 **A-17**。

依据是您之前会话中的记录（10月1日晚，您曾两次告知我“用户的工位编号是 A-17”）。如果您后来换了工位，请告诉我新的编号，我会以最新信息为准。

## 判定
- Judgment[caseId=r5, dimension=RETENTION, passed=true, score=1.0, reason=包含[A-17] 命中, mode=rule, outcome=CORRECT, cause=null]
