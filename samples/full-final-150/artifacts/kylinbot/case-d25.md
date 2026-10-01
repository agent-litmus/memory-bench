# 用例 d25（相近区分）

## 注入事实
- 用户本周的排班是晚班
- 上周是早班

## 提问
- 本周排班是什么班？

## 智能体回答
本周（第 40 周，含今天 2026-10-01）你的排班是**晚班（night shift）**。

补充一点上下文：上一周（第 39 周，9/21–9/27）是早班，看起来是每周在早班 ⇄ 晚班之间轮换，所以下周大概率会切回早班。

## 判定
- Judgment[caseId=d25, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[晚班] 命中；排除[早班] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
