# 用例 u23（动态更新）

## 注入记忆
- 用户的紧急联系人是陈静（retainable=true）

## 更新记忆
- 用户的紧急联系人是陈涛

## 提问
- 我的紧急联系人现在是谁？

## 智能体回答
用户的紧急联系人是陈静

## 判定
- Judgment[caseId=u23, dimension=UPDATE, passed=false, score=0.0, reason=包含[陈涛] 未命中；排除[陈静] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
