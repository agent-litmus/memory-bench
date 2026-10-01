# 用例 d14（相近区分）

## 注入记忆
- 用户昨天的加班时长是 2 小时（retainable=true）
- 上周的调休天数是 1 天（retainable=true）

## 提问
- 昨天加班多久？

## 智能体回答
用户昨天的加班时长是 2 小时

## 判定
- Judgment[caseId=d14, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[2 小时] 命中；排除[1 天] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
