# 用例 d4（相近区分）

## 注入记忆
- 用户的测试密钥是 sk-test-001（retainable=true）
- 用户的生产账号是 prod_admin（retainable=true）

## 提问
- 生产账号是什么？

## 智能体回答
用户的生产账号是 prod_admin

## 判定
- Judgment[caseId=d4, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[prod_admin] 命中；排除[sk-test-001] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
