# 用例 u24（动态更新）

## 注入记忆
- 用户的日志级别设置为 DEBUG（retainable=true）

## 更新记忆
- 用户的日志级别设置为 WARN

## 提问
- 现在的日志级别是什么？

## 智能体回答
用户的日志级别设置为 WARN

## 判定
- Judgment[caseId=u24, dimension=UPDATE, passed=true, score=1.0, reason=包含[WARN] 命中；排除[DEBUG] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
