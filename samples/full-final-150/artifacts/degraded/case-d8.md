# 用例 d8（相近区分）

## 注入记忆
- 用户上周出差去了成都（retainable=true）
- 用户下周的出差城市是西安（retainable=true）

## 提问
- 下周的行程城市是哪个？

## 智能体回答
用户上周出差去了成都

## 判定
- Judgment[caseId=d8, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[西安] 未命中；排除[成都] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
