# 用例 d24（相近区分）

## 注入事实
- 用户的工位在东侧靠窗
- 会议室在西侧尽头

## 提问
- 我的工位在哪里？

## 智能体回答
你的工位在**东侧靠窗**的位置。

另外按照你之前给的信息，会议室在西侧尽头——从你的工位过去需要往西穿过整个办公区。

## 判定
- Judgment[caseId=d24, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[东侧靠窗] 命中；排除[西侧尽头] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
