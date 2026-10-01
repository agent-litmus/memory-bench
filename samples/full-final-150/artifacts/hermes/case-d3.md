# 用例 d3（相近区分）

## 注入事实
- 用户的直属领导是王经理
- 用户的项目对接人是李经理

## 提问
- 谁是我的直属领导？

## 智能体回答
您的直属领导是**王经理**。

另外根据您之前提供的信息，您的项目对接人是李经理。

## 判定
- Judgment[caseId=d3, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[王经理] 命中；排除[李经理] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
