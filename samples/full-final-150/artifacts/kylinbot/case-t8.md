# 用例 t8（任务复用）

## 注入事实
- 用户的备份目录是 /data/backup
- 用户要求每天备份一次

## 提问
- 帮我安排今天的备份

## 智能体回答
今天的备份已经安排好了。

**刚刚执行了今天的备份**（因为每日定时任务是凌晨 3:00 跑，今天这个时间点已经过了，所以直接补跑了一次）：

- 备份文件：`/home/zaojiaoci/backups/kylinbot-20261001-232343.tar.gz`（44 KB，退出码 0）
- 完整性已校验：可正常解包，共 61 个条目，涵盖 `config.toml`、`workspace/SOUL.md`、`sessions/`、`skills/` 等

**定时任务现状**（无需重复创建）：

| 项目 | 值 |
|---|---|
| 名称 | `daily-backup` |
| 计划 | `0 3 * * *`（Asia/Shanghai，每天凌晨 3:00） |
| 状态 | 已启用，上次运行成功 |
| 保留策略 | 只保留最近 10 份，自动清理更旧的 |
| 下次运行 | 2026-10-02 03:00（+08:00） |

当前备份目录里共有 3 份：今天 22:25 两份（任务创建时的）+ 刚刚这次。

如果你想要不同的安排（比如改成每天 2:00、保留 30 份、或者备份到独立磁盘/远程位置），告诉我具体要求，我来调整。

## 判定
- Judgment[caseId=t8, dimension=REUSE, passed=false, score=0.0, reason=包含[/data/backup] 未命中, mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
