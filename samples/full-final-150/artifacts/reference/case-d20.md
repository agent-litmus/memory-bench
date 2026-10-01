# 用例 d20（相近区分）

## 注入记忆
- 用户负责的模块是订单中心（retainable=true）
- 同事维护的是库存中心（retainable=true）

## 提问
- 我负责的模块是什么？

## 智能体回答
用户负责的模块是订单中心

## 判定
- Judgment[caseId=d20, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[订单中心] 命中；排除[库存中心] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
