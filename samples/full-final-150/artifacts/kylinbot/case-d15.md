# 用例 d15（相近区分）

## 注入事实
- 用户的生产库端口是 5432
- 测试库运行在 5433

## 提问
- 生产库的端口是多少？

## 智能体回答
生产库的端口是 **5432**。

（顺带一提，测试库在 5433，别搞混了。）

## 判定
- Judgment[caseId=d15, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[5432] 命中；排除[5433] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
