# 用例 d24（相近区分）

## 注入事实
- 用户的工位在东侧靠窗
- 会议室在西侧尽头

## 提问
- 我的工位在哪里？

## 智能体回答
你的工位在**东侧靠窗**。

补充一句：这条信息目前在记忆里有两条重复记录（`user_desk_location` 和 `user_workstation_location`，内容一致），需要的话我可以帮你把重复项清理掉，只保留一条。

## 判定
- Judgment[caseId=d24, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[东侧靠窗] 命中；排除[西侧尽头] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
