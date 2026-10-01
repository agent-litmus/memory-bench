# 用例 d10（相近区分）

## 注入记忆
- 用户昨天买的书是《重构》（retainable=true）
- 用户今天在读的报告是《演进式架构》（retainable=true）

## 提问
- 今天在读的是哪份材料？

## 智能体回答
用户昨天买的书是《重构》

## 判定
- Judgment[caseId=d10, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[演进式架构] 未命中；排除[重构] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
