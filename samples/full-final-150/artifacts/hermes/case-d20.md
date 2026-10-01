# 用例 d20（相近区分）

## 注入事实
- 用户负责的模块是订单中心
- 同事维护的是库存中心

## 提问
- 我负责的模块是什么？

## 智能体回答
根据你在本次对话中的说明：你负责的模块是**订单中心**（你的同事维护的是库存中心）。

有什么需要我帮忙的吗？比如排查订单相关的问题、梳理订单与库存中心的交互逻辑等。

## 判定
- Judgment[caseId=d20, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[订单中心] 命中；排除[库存中心] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
