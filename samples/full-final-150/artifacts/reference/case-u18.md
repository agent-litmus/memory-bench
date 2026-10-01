# 用例 u18（动态更新）

## 注入记忆
- 用户的出差标准是每晚 300 元（retainable=true）

## 更新记忆
- 用户的出差标准是每晚 450 元

## 提问
- 出差住宿标准是多少？

## 智能体回答
用户的出差标准是每晚 450 元

## 判定
- Judgment[caseId=u18, dimension=UPDATE, passed=true, score=1.0, reason=包含[450] 命中；排除[300] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
