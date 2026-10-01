# 用例 u11（动态更新）

## 注入事实
- 用户的服务器配置是 4 核 8G

## 更新事实
- 用户的服务器配置是 8 核 16G

## 提问
- 现在的服务器配置是什么？

## 智能体回答
根据你在本次对话中最新的说明，当前服务器配置是 **8核16G**。

本次对话中你先提到过4核8G，随后更新为8核16G，以最新的为准。

如果这个配置要用于部署或调优，随时告诉我具体需求即可。

## 判定
- Judgment[caseId=u11, dimension=UPDATE, passed=false, score=0.0, reason=包含[8 核 16G] 未命中；排除[4 核 8G] 未出现(合格), mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
