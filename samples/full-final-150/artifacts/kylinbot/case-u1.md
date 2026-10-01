# 用例 u1（动态更新）

## 注入事实
- 用户当前的岗位是订单录入员

## 更新事实
- 用户当前的岗位是客户关系管理专员

## 提问
- 我现在的岗位是什么？

## 智能体回答
你现在的岗位是**客户关系管理专员**（CRM Specialist）。

## 判定
- Judgment[caseId=u1, dimension=UPDATE, passed=true, score=1.0, reason=包含[客户关系管理专员] 命中；排除[订单录入员] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
