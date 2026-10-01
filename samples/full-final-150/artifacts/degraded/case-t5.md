# 用例 t5（任务复用）

## 注入记忆
- 用户的报告输出目录是 /home/user/reports（retainable=true）
- 用户要求所有报告都写到该目录（retainable=true）

## 提问
- 把这份报告写到我常用的位置

## 智能体回答
用户的报告输出目录是 /home/user/reports

## 判定
- Judgment[caseId=t5, dimension=REUSE, passed=true, score=1.0, reason=包含[/home/user/reports] 命中, mode=rule, outcome=CORRECT, cause=null]
