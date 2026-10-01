# 用例 u3（动态更新）

## 注入记忆
- 用户的项目截止日期是 6 月 30 日（retainable=true）

## 更新记忆
- 用户的项目截止日期是 7 月 15 日

## 提问
- 项目什么时候截止？

## 智能体回答
用户的项目截止日期是 6 月 30 日

## 判定
- Judgment[caseId=u3, dimension=UPDATE, passed=false, score=0.0, reason=包含[7 月 15 日] 未命中；排除[6 月 30 日] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
