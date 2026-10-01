# 用例 d14（相近区分）

## 注入事实
- 用户昨天的加班时长是 2 小时
- 上周的调休天数是 1 天

## 提问
- 昨天加班多久？

## 智能体回答
您昨天（2026年10月1日）的加班时长是 **2 小时**。

## 判定
- Judgment[caseId=d14, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[2 小时] 命中；排除[1 天] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
