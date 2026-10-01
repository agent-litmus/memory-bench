# 用例 d2（相近区分）

## 注入事实
- 用户上周二去了上海出差
- 用户上周三去了杭州出差

## 提问
- 我周几去的杭州？

## 智能体回答
您是**上周三**去的杭州出差，也就是 **2026年9月23日**——紧跟着前一天（周二9月22日）的上海出差，算是一次连续两天的长三角行程。

## 判定
- Judgment[caseId=d2, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[周三] 命中；排除[上海] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
