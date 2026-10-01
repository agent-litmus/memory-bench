# 用例 u13（动态更新）

## 注入记忆
- 用户的工位在 3 楼（retainable=true）

## 更新记忆
- 用户的工位在 7 楼

## 提问
- 我的工位在几楼？

## 智能体回答
用户的工位在 3 楼

## 判定
- Judgment[caseId=u13, dimension=UPDATE, passed=false, score=0.0, reason=包含[7 楼] 未命中；排除[3 楼] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
