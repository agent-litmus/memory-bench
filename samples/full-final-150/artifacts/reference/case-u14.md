# 用例 u14（动态更新）

## 注入记忆
- 用户的部署环境是测试环境（retainable=true）

## 更新记忆
- 用户的部署环境是预发布环境

## 提问
- 现在部署到哪个环境？

## 智能体回答
用户的部署环境是预发布环境

## 判定
- Judgment[caseId=u14, dimension=UPDATE, passed=true, score=1.0, reason=包含[预发布] 命中；排除[测试环境] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
