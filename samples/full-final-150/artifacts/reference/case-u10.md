# 用例 u10（动态更新）

## 注入记忆
- 用户的周会时间是周一（retainable=true）

## 更新记忆
- 用户的周会时间是周五

## 提问
- 周会改到哪天了？

## 智能体回答
用户的周会时间是周五

## 判定
- Judgment[caseId=u10, dimension=UPDATE, passed=true, score=1.0, reason=包含[周五] 命中；排除[周一] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
