# 用例 u11（动态更新）

## 注入记忆
- 用户的服务器配置是 4 核 8G（retainable=true）

## 更新记忆
- 用户的服务器配置是 8 核 16G

## 提问
- 现在的服务器配置是什么？

## 智能体回答
用户的服务器配置是 4 核 8G

## 判定
- Judgment[caseId=u11, dimension=UPDATE, passed=false, score=0.0, reason=包含[8 核 16G] 未命中；排除[4 核 8G] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
