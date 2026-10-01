# 用例 r6（长期保持） ｜ 跨会话

## 注入事实
- 用户每周三下午有团队例会

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我周几下午有团队例会？

## 智能体回答
您**每周三下午**有团队例会（这是您之前告诉我并记录下来的信息）。

如果需要，我可以帮您：
- 在安排其他事项时避开周三下午- 例会前提醒您准备议题或材料

## 判定
- Judgment[caseId=r6, dimension=RETENTION, passed=true, score=1.0, reason=包含[周三] 命中, mode=rule, outcome=CORRECT, cause=null]
