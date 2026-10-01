# 用例 u21（动态更新）

## 注入记忆
- 用户使用的工单系统是 TicketOld（retainable=true）

## 更新记忆
- 用户使用的工单系统是 TicketNew

## 提问
- 现在用哪个工单系统？

## 智能体回答
用户使用的工单系统是 TicketNew

## 判定
- Judgment[caseId=u21, dimension=UPDATE, passed=true, score=1.0, reason=包含[TicketNew] 命中；排除[TicketOld] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
