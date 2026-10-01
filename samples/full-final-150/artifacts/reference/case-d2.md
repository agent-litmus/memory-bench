# 用例 d2（相近区分）

## 注入记忆
- 用户上周二去了上海出差（retainable=true）
- 用户上周三去了杭州出差（retainable=true）

## 提问
- 我周几去的杭州？

## 智能体回答
用户上周三去了杭州出差

## 判定
- Judgment[caseId=d2, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[周三] 命中；排除[上海] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
