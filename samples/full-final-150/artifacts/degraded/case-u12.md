# 用例 u12（动态更新）

## 注入记忆
- 用户的默认分支是 master（retainable=true）

## 更新记忆
- 用户的默认分支是 main

## 提问
- 默认分支是哪个？

## 智能体回答
用户的默认分支是 master

## 判定
- Judgment[caseId=u12, dimension=UPDATE, passed=false, score=0.0, reason=包含[main] 未命中；排除[master] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
