# 用例 d18（相近区分）

## 注入记忆
- 用户的门禁卡有效期到 2026 年（retainable=true）
- 停车证 2025 年作废（retainable=true）

## 提问
- 门禁卡的有效期到什么时候？

## 智能体回答
用户的门禁卡有效期到 2026 年

## 判定
- Judgment[caseId=d18, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[2026] 命中；排除[2025] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
