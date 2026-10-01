# 用例 u13（动态更新）

## 注入记忆
- 用户的工位在 3 楼（retainable=true）

## 更新记忆
- 用户的工位在 7 楼

## 提问
- 我的工位在几楼？

## 智能体回答
用户的工位在 7 楼

## 判定
- Judgment[caseId=u13, dimension=UPDATE, passed=true, score=1.0, reason=包含[7 楼] 命中；排除[3 楼] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
