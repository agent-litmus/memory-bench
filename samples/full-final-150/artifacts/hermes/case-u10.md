# 用例 u10（动态更新）

## 注入事实
- 用户的周会时间是周一

## 更新事实
- 用户的周会时间是周五

## 提问
- 周会改到哪天了？

## 智能体回答
周会改到**周五**了。

最开始你说的是周一，后来更新为周五，以最新的“周五”为准。

## 判定
- Judgment[caseId=u10, dimension=UPDATE, passed=false, score=0.0, reason=包含[周五] 命中；排除[周一] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
