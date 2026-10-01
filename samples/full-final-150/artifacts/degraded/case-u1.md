# 用例 u1（动态更新）

## 注入记忆
- 用户当前的岗位是订单录入员（retainable=true）

## 更新记忆
- 用户当前的岗位是客户关系管理专员

## 提问
- 我现在的岗位是什么？

## 智能体回答
用户当前的岗位是订单录入员

## 判定
- Judgment[caseId=u1, dimension=UPDATE, passed=false, score=0.0, reason=包含[客户关系管理专员] 未命中；排除[订单录入员] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
