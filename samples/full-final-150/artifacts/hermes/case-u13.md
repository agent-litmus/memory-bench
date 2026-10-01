# 用例 u13（动态更新）

## 注入事实
- 用户的工位在 3 楼

## 更新事实
- 用户的工位在 7 楼

## 提问
- 我的工位在几楼？

## 智能体回答
您的工位在 **7楼**。

这是根据您刚才的最新说明（之前提到过3楼，但后来更正为7楼了）。

## 判定
- Judgment[caseId=u13, dimension=UPDATE, passed=false, score=0.0, reason=包含[7 楼] 未命中；排除[3 楼] 未出现(合格), mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
