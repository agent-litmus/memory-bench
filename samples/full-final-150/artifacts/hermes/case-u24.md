# 用例 u24（动态更新）

## 注入事实
- 用户的日志级别设置为 DEBUG

## 更新事实
- 用户的日志级别设置为 WARN

## 提问
- 现在的日志级别是什么？

## 智能体回答
当前的日志级别是 **WARN**。

（您先设置为 DEBUG，之后更新为 WARN，以最新的设置为准。）

## 判定
- Judgment[caseId=u24, dimension=UPDATE, passed=false, score=0.0, reason=包含[WARN] 命中；排除[DEBUG] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
