# 用例 d24（相近区分）

## 注入记忆
- 用户的工位在东侧靠窗（retainable=true）
- 会议室在西侧尽头（retainable=true）

## 提问
- 我的工位在哪里？

## 智能体回答
用户的工位在东侧靠窗

## 判定
- Judgment[caseId=d24, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[东侧靠窗] 命中；排除[西侧尽头] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
