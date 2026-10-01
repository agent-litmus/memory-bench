# 用例 r11（长期保持） ｜ 跨会话

## 注入事实
- 用户所在的公司叫星澜科技

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我在哪家公司上班？

## 智能体回答
您在**星澜科技**上班。这是您之前告诉我并记录在记忆中的信息，多次对话记录一致。

如果换了工作或信息有变动，随时告诉我，我会更新记忆。

## 判定
- Judgment[caseId=r11, dimension=RETENTION, passed=true, score=1.0, reason=包含[星澜科技] 命中, mode=rule, outcome=CORRECT, cause=null]
