# 用例 u19（动态更新） ｜ 跨会话

## 注入事实
- 用户的备份策略是每周全量

## 更新事实
- 用户的备份策略是每日增量

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 现在的备份策略是什么？

## 智能体回答
当前**没有真正生效的自动备份策略**——配置里声明了备份能力，但没有任何调度在跑，实际备份看起来是手动零散触发的。下面是核查结果：

## 1. 配置里声明的策略
`config.toml`（工作区那份和 `~/.kylinbot/config.toml` 内容一致）：

```toml
[backup]
enabled        = true
max_keep       = 10
include_dirs   = ["config", "memory", "audit", "knowledge"]
destination_dir = "state/backups"
compress       = true
encrypt        = false

[data_retention]
enabled        = false
retention_days = 90
dry_run        = false
```

即：开启备份、保留最近 10 份、压缩不加密、目标目录 `state/backups`。

## 2. 但调度层是空的
- `cron_list` → 空；工作区 `cron/jobs.db` 里 `cron_jobs` 表 **0 条记录**
- `crontab -l` → 无用户 crontab
- `systemctl --user list-timers` → 只有麒麟系统自带的升级/清理定时器，**没有备份任务**

所以严格说，现在不存在"定期备份"这个动作。

## 3. 实际存在的备份文件
`~/backups/` 下有 3 个包，都是 `.kylinbot/` 的整体快照（含 config.toml、daemon_state.json、`.secret_key`、workspace 下的 memory/sessions/skills/state 等）：

| 文件 | 时间 |
|---|---|
| kylinbot-20261001-222541.tar.gz | 10-01 22:25:41 |
| kylinbot-20261001-222546.tar.gz | 10-01 22:25:46 |
| kylinbot-20261001-232343.tar.gz | 10-01 23:23:43 |

时间点不规则（其中两个相隔仅 5 秒），更像手动执行的产物。

## 4. 几个需要你注意的不一致
1. **目录对不上**：配置写 `state/backups`，但这个目录在系统里根本不存在，实际备份落在 `~/backups`。
2. **范围对不上**：配置说只备 `config/memory/audit/knowledge`，实际包是整个 `.kylinbot/`（含 sessions、skills、devices.db）。
3. **加密关闭**：`encrypt = false`，而归档里包含 `.secret_key` 和会话数据库，属于**明文敏感数据**，建议开启加密或把 `.secret_key` 排除。
4. **保留策略未生效**：`max_keep = 10`，但目前只有 3 份、也没有自动清理在跑。

需要我把它落成一个真正的定时策略吗？比如加一个 cron 任务（每天固定时间备份 + 按 max_keep 自动清理），并顺手把 `destination_dir`、`encrypt`、排除项这几个不一致修正掉。你确认后我就配置。

## 判定
- Judgment[caseId=u19, dimension=UPDATE, passed=false, score=0.0, reason=包含[每日增量] 未命中；排除[每周全量] 未出现(合格), mode=rule, outcome=OMISSION, cause=PERSIST_MISSING]
