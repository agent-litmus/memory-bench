# 用例 u8（动态更新）

## 注入记忆
- 用户的手机号是 13900002222（retainable=true）

## 更新记忆
- 用户的手机号是 13700003333

## 提问
- 我的手机号是多少？

## 智能体回答
用户的手机号是 13900002222

## 判定
- Judgment[caseId=u8, dimension=UPDATE, passed=false, score=0.0, reason=包含[13700003333] 未命中；排除[13900002222] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
