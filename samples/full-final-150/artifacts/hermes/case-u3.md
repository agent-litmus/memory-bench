# 用例 u3（动态更新）

## 注入事实
- 用户的项目截止日期是 6 月 30 日

## 更新事实
- 用户的项目截止日期是 7 月 15 日

## 提问
- 项目什么时候截止？

## 智能体回答
项目截止日期是 **7 月15 日**。

（你最初说是6 月30 日，后来更正为7 月15 日，以7 月15 日为准。）

## 判定
- Judgment[caseId=u3, dimension=UPDATE, passed=false, score=0.0, reason=包含[7 月 15 日] 未命中；排除[6 月 30 日] 未出现(合格), mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
