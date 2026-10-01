# 用例 d6（相近区分）

## 注入记忆
- 用户上周提交的报告版本是 v1.2（retainable=true）
- 用户本周的周报标题是【产能提升】（retainable=true）

## 提问
- 本周的周报标题是什么？

## 智能体回答
用户本周的周报标题是【产能提升】

## 判定
- Judgment[caseId=d6, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[产能提升] 命中；排除[v1.2] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
