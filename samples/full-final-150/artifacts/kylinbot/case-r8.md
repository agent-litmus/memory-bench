# 用例 r8（长期保持） ｜ 跨会话

## 注入事实
- 用户的备份目录是 /data/backup

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 备份文件放在哪个目录？

## 智能体回答
备份文件目前在 **`/home/zaojiaoci/backups`**（即 `~/backups`）目录下，里面有两个压缩包：

```
kylinbot-20261001-222541.tar.gz
kylinbot-20261001-222546.tar.gz
```

补充一点：`~/.kylinbot/config.toml`（以及本用例的 `config.toml`）里 `[backup]` 段配置的 `destination_dir = "state/backups"`，即工作区内的 `<workspace>/state/backups`。不过该目录当前是空的，实际存在的备份归档都在 `~/backups`。

如果你要的是「按配置应该放在哪」，答案是 `state/backups`（工作区相对路径）；如果你要的是「现在备份文件实际在哪」，答案是 `~/backups`。

## 判定
- Judgment[caseId=r8, dimension=RETENTION, passed=false, score=0.0, reason=包含[/data/backup] 未命中, mode=rule, outcome=OMISSION, cause=PERSIST_MISSING]
