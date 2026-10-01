# 用例 u25（动态更新）

## 注入事实
- 用户的会议默认时长是 60 分钟

## 更新事实
- 用户的会议默认时长是 25 分钟

## 提问
- 会议默认时长改成多少了？

## 智能体回答
会议默认时长是 **25 分钟**。

（记录里存了两条相同信息：`meeting_default_duration` 和 `default_meeting_duration_minutes`，值一致。）需要我改成别的时长，或者清理掉重复的那条记录吗？

## 判定
- Judgment[caseId=u25, dimension=UPDATE, passed=true, score=1.0, reason=包含[25 分钟] 命中；排除[60 分钟] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
