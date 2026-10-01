# 用例 r6（长期保持） ｜ 跨会话

## 注入事实
- 用户每周三下午有团队例会

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我周几下午有团队例会？

## 智能体回答
你每周**周三**下午有团队例会。

## 判定
- Judgment[caseId=r6, dimension=RETENTION, passed=true, score=1.0, reason=包含[周三] 命中, mode=rule, outcome=CORRECT, cause=null]
