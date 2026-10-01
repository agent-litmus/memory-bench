# 用例 u25（动态更新）

## 注入记忆
- 用户的会议默认时长是 60 分钟（retainable=true）

## 更新记忆
- 用户的会议默认时长是 25 分钟

## 提问
- 会议默认时长改成多少了？

## 智能体回答
用户的会议默认时长是 60 分钟

## 判定
- Judgment[caseId=u25, dimension=UPDATE, passed=false, score=0.0, reason=包含[25 分钟] 未命中；排除[60 分钟] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
