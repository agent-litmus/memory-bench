# 用例 u25（动态更新）

## 注入事实
- 用户的会议默认时长是 60 分钟

## 更新事实
- 用户的会议默认时长是 25 分钟

## 提问
- 会议默认时长改成多少了？

## 智能体回答
现在是 **25 分钟**。

您先说默认60 分钟，随后更新为25 分钟；今天又先改为60 分钟，最后（刚才）再次定为25 分钟。所以当前生效的默认时长是 **25 分钟**。

## 判定
- Judgment[caseId=u25, dimension=UPDATE, passed=false, score=0.0, reason=包含[25 分钟] 命中；排除[60 分钟] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
