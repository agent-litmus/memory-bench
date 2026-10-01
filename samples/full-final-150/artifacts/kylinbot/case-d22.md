# 用例 d22（相近区分）

## 注入事实
- 用户的年度体检安排在 9 月
- 年度团建定在 5 月

## 提问
- 体检安排在几月？

## 智能体回答
根据记忆，你的年度体检安排在 **9 月**（每年一次）。2026 年的体检时段是 2026 年 9 月，下一次预计为 2027 年 9 月。

## 判定
- Judgment[caseId=d22, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[9 月] 命中；排除[5 月] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
