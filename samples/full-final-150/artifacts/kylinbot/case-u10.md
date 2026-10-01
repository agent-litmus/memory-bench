# 用例 u10（动态更新）

## 注入事实
- 用户的周会时间是周一

## 更新事实
- 用户的周会时间是周五

## 提问
- 周会改到哪天了？

## 智能体回答
我查了一下记忆，但**没有找到任何“周会改期”的记录**——也就是说我这边没有你改到哪天的那条信息。

而且现有记录本身是冲突的：

| 记忆条目 | 内容 |
|---|---|
| `user_weekly_meeting` | 周会时间是**周五** |
| `weekly_meeting_day` | 周会时间是**周五** |
| `user_weekly_meeting_day` | 周会时间是**周一** |

三条里两条说周五、一条说周一，没法确定哪个是最新的，所以我不敢直接给你一个日期。

麻烦你确认一下：改期后的周会到底是**周几**？告诉我后我会把重复/过时的条目清掉，只留一条准确记录（比如用 `weekly_meeting_day` 统一存），下次就不会再乱了。

## 判定
- Judgment[caseId=u10, dimension=UPDATE, passed=false, score=0.0, reason=包含[周五] 命中；排除[周一] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
