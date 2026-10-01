# 用例 t13（任务复用）

## 注入记忆
- 用户的报告输出目录是 /home/user/reports（retainable=true）
- 用户要求所有报告按周归档（retainable=true）

## 提问
- 把这份报告存到我常用的目录

## 智能体回答
用户的报告输出目录是 /home/user/reports

## 判定
- Judgment[caseId=t13, dimension=REUSE, passed=true, score=1.0, reason=包含[/home/user/reports] 命中, mode=rule, outcome=CORRECT, cause=null]
