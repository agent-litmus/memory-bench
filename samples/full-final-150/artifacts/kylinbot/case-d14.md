# 用例 d14（相近区分）

## 注入事实
- 用户昨天的加班时长是 2 小时
- 上周的调休天数是 1 天

## 提问
- 昨天加班多久？

## 智能体回答
昨天（2026-09-30）你加班了 **2 小时**。

顺带一提，你的记忆里有两条内容完全相同的加班记录（`overtime_2026-09-30` 和 `overtime_2026_09_30`），属于重复录入。需要我合并去重吗？

## 判定
- Judgment[caseId=d14, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[2 小时] 命中；排除[1 天] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
