# 用例 d3（相近区分）

## 注入记忆
- 用户的直属领导是王经理（retainable=true）
- 用户的项目对接人是李经理（retainable=true）

## 提问
- 谁是我的直属领导？

## 智能体回答
用户的直属领导是王经理

## 判定
- Judgment[caseId=d3, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[王经理] 命中；排除[李经理] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
