# 用例 u21（动态更新）

## 注入事实
- 用户使用的工单系统是 TicketOld

## 更新事实
- 用户使用的工单系统是 TicketNew

## 提问
- 现在用哪个工单系统？

## 智能体回答
现在使用的是 **TicketNew**。

这是您本次会话中最新的说明（在"TicketOld"之后更新为 TicketNew），以最新说法为准。需要基于 TicketNew做什么操作，随时告诉我。

## 判定
- Judgment[caseId=u21, dimension=UPDATE, passed=false, score=0.0, reason=包含[TicketNew] 命中；排除[TicketOld] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
