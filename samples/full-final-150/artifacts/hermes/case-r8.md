# 用例 r8（长期保持） ｜ 跨会话

## 注入事实
- 用户的备份目录是 /data/backup

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 备份文件放在哪个目录？

## 智能体回答
根据之前的记录，你的备份文件目录是 **`/data/backup`**。

这是你在之前会话中确定的备份目录（当时的备份要求是每天备份一次）。如果目录或策略有变动，告诉我即可帮你更新。

## 判定
- Judgment[caseId=r8, dimension=RETENTION, passed=true, score=1.0, reason=包含[/data/backup] 命中, mode=rule, outcome=CORRECT, cause=null]
