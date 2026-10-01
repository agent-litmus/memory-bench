# 用例 u13（动态更新）

## 注入事实
- 用户的工位在 3 楼

## 更新事实
- 用户的工位在 7 楼

## 提问
- 我的工位在几楼？

## 智能体回答
我在记忆里找到两条**互相冲突**的记录，没法确定哪个是对的：

- `user_workstation_floor`：工位在 **7 楼**
- `user_workstation_location`：工位在 **3 楼**

这两条都是 core 级记忆，没有先后或时间戳能判断哪条更新，所以我不能替你猜。

你确认一下是几楼？我会把错误的那条删掉、保留正确的，避免以后再冲突。

## 判定
- Judgment[caseId=u13, dimension=UPDATE, passed=false, score=0.0, reason=包含[7 楼] 命中；排除[3 楼] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
