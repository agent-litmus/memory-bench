# 用例 d5（相近区分）

## 注入记忆
- 用户的测试库账号是 test_rw（retainable=true）
- 用户的生产库连接串是 jdbc:prod:3306（retainable=true）

## 提问
- 生产库的连接串是什么？

## 智能体回答
用户的生产库连接串是 jdbc:prod:3306

## 判定
- Judgment[caseId=d5, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[jdbc:prod:3306] 命中；排除[test_rw] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
