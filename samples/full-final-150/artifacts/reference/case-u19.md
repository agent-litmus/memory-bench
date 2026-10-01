# 用例 u19（动态更新）

## 注入记忆
- 用户的备份策略是每周全量（retainable=true）

## 更新记忆
- 用户的备份策略是每日增量

## 提问
- 现在的备份策略是什么？

## 智能体回答
用户的备份策略是每日增量

## 判定
- Judgment[caseId=u19, dimension=UPDATE, passed=true, score=1.0, reason=包含[每日增量] 命中；排除[每周全量] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
