# 用例 t9（任务复用）

## 注入记忆
- 用户负责订单录入工作（retainable=true）
- 用户的团队规模是 8 人（retainable=true）

## 提问
- 结合我的团队规模给一个排班建议

## 智能体回答
用户负责订单录入工作

## 判定
- Judgment[caseId=t9, dimension=REUSE, passed=false, score=0.0, reason=包含[8 人] 未命中, mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
