# 用例 c15（记忆调用）

## 注入事实
- 用户要求所有金额保留两位小数

## 提问
- 这笔费用要保留几位小数？

## 智能体回答
两位小数。所有金额都按两位小数呈现，例如 12.5 → 12.50、1234 → 1234.00。

（这条偏好我这边留着两条重复记录，内容一致，需要的话可以帮你合并成一条。）

## 判定
- Judgment[caseId=c15, dimension=RECALL, passed=true, score=1.0, reason=包含[两位小数] 命中, mode=rule, outcome=CORRECT, cause=null]
