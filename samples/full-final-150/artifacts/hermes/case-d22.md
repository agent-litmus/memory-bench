# 用例 d22（相近区分）

## 注入事实
- 用户的年度体检安排在 9 月
- 年度团建定在 5 月

## 提问
- 体检安排在几月？

## 智能体回答
体检安排在 **9 月**。

顺便回顾一下目前记录的年度安排：
- 年度体检：9 月- 年度团建：5 月

## 判定
- Judgment[caseId=d22, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[9 月] 命中；排除[5 月] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
