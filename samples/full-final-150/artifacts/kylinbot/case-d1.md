# 用例 d1（相近区分）

## 注入事实
- 张三是销售部经理
- 李四是市场部经理

## 提问
- 谁负责销售？

## 智能体回答
销售由 **张三** 负责——他是销售部经理。

## 判定
- Judgment[caseId=d1, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[张三] 命中；排除[李四] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
