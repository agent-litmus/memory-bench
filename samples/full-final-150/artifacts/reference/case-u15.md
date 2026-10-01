# 用例 u15（动态更新）

## 注入记忆
- 用户的报销额度是每月 800 元（retainable=true）

## 更新记忆
- 用户的报销额度是每月 1200 元

## 提问
- 我的报销额度是多少？

## 智能体回答
用户的报销额度是每月 1200 元

## 判定
- Judgment[caseId=u15, dimension=UPDATE, passed=true, score=1.0, reason=包含[1200] 命中；排除[800] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
