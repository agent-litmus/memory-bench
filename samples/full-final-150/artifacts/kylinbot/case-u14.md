# 用例 u14（动态更新）

## 注入事实
- 用户的部署环境是测试环境

## 更新事实
- 用户的部署环境是预发布环境

## 提问
- 现在部署到哪个环境？

## 智能体回答
当前部署到的是**预发布环境（staging / pre-release）**。

需要我帮你切换或确认某个具体服务的部署目标吗？

## 判定
- Judgment[caseId=u14, dimension=UPDATE, passed=true, score=1.0, reason=包含[预发布] 命中；排除[测试环境] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
