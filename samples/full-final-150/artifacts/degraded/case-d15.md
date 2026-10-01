# 用例 d15（相近区分）

## 注入记忆
- 用户的生产库端口是 5432（retainable=true）
- 测试库运行在 5433（retainable=true）

## 提问
- 生产库的端口是多少？

## 智能体回答
用户的生产库端口是 5432

## 判定
- Judgment[caseId=d15, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[5432] 命中；排除[5433] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
