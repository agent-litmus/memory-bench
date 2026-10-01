# 用例 d2（相近区分）

## 注入记忆
- 用户上周二去了上海出差（retainable=true）
- 用户上周三去了杭州出差（retainable=true）

## 提问
- 我周几去的杭州？

## 智能体回答
用户上周二去了上海出差

## 判定
- Judgment[caseId=d2, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[周三] 未命中；排除[上海] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
