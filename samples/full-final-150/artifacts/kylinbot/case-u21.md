# 用例 u21（动态更新）

## 注入事实
- 用户使用的工单系统是 TicketOld

## 更新事实
- 用户使用的工单系统是 TicketNew

## 提问
- 现在用哪个工单系统？

## 智能体回答
我查了长期记忆，这里有个冲突，先跟你说清楚：

**当前存在两条互相矛盾的记录：**
- `user_ticket_system` → **TicketNew**（记录于 2026-10-01 23:25:29，最新）
- `user_ticketing_system` → **TicketOld**（创建于 22:24，最后更新于 23:25:25）

按时间看，最新写入的是 **TicketNew**；但另一条记录表示 **TicketOld**，两条又几乎是同一时间点反复出现（22:24 和 23:25 各出现一次「先 TicketOld、后 TicketNew」的序列），所以我不能确定哪条才代表你现在的真实情况。

你现在的工单系统到底是 **TicketOld** 还是 **TicketNew**？告诉我一声，我把过期的那条清掉，只保留正确的。

## 判定
- Judgment[caseId=u21, dimension=UPDATE, passed=false, score=0.0, reason=包含[TicketNew] 命中；排除[TicketOld] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
