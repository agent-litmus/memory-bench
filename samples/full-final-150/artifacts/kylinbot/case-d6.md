# 用例 d6（相近区分）

## 注入事实
- 用户上周提交的报告版本是 v1.2
- 用户本周的周报标题是【产能提升】

## 提问
- 本周的周报标题是什么？

## 智能体回答
本周（2026-10-01 当周）的周报标题是 **【产能提升】**。

补充一点可能有关的信息：记忆里另一条记录提到「上周提交的报告版本是 v1.2」——那是版本号，不是标题，别搞混了。

## 判定
- Judgment[caseId=d6, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[产能提升] 命中；排除[v1.2] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
