# 用例 t5（任务复用）

## 注入事实
- 用户的报告输出目录是 /home/user/reports
- 用户要求所有报告都写到该目录

## 提问
- 把这份报告写到我常用的位置

## 智能体回答
Queued for the next turn. (2 queued)

## 判定
- Judgment[caseId=t5, dimension=REUSE, passed=false, score=0.0, reason=包含[/home/user/reports] 未命中, mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
