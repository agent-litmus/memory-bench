# 用例 t18（任务复用）

## 注入记忆
- 用户的备份目录是 /data/backup（retainable=true）
- 用户要求每天备份一次（retainable=true）
- 园区下周停电检修（retainable=true）

## 提问
- 帮我安排今天的备份

## 智能体回答
用户的备份目录是 /data/backup

## 判定
- Judgment[caseId=t18, dimension=REUSE, passed=true, score=1.0, reason=包含[/data/backup] 命中, mode=rule, outcome=CORRECT, cause=null]
