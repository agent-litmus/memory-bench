# 用例 d25（相近区分）

## 注入记忆
- 用户本周的排班是晚班（retainable=true）
- 上周是早班（retainable=true）

## 提问
- 本周排班是什么班？

## 智能体回答
用户本周的排班是晚班

## 判定
- Judgment[caseId=d25, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[晚班] 命中；排除[早班] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
