# 用例 r15（长期保持） ｜ 跨会话

## 注入事实
- 用户的紧急联系人是配偶陈静

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我的紧急联系人是谁？

## 智能体回答
你的紧急联系人是**陈静**，关系是**配偶**。

这条信息记录在长期记忆里（core 类别），共两条一致记录：
- `emergency_contact`：配偶 陈静
- `user_emergency_contact`：配偶陈静（spouse: Chen Jing）

需要我补充或更新联系方式（如电话号码）吗？

## 判定
- Judgment[caseId=r15, dimension=RETENTION, passed=true, score=1.0, reason=包含[陈静] 命中, mode=rule, outcome=CORRECT, cause=null]
