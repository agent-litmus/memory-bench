# 用例 d22（相近区分）

## 注入记忆
- 用户的年度体检安排在 9 月（retainable=true）
- 年度团建定在 5 月（retainable=true）

## 提问
- 体检安排在几月？

## 智能体回答
用户的年度体检安排在 9 月

## 判定
- Judgment[caseId=d22, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[9 月] 命中；排除[5 月] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
