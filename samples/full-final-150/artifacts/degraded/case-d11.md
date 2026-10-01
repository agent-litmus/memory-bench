# 用例 d11（相近区分）

## 注入记忆
- 用户的小号昵称是小李（retainable=true）
- 用户的工作账号是 wang_qc（retainable=true）

## 提问
- 工作账号是什么？

## 智能体回答
用户的小号昵称是小李

## 判定
- Judgment[caseId=d11, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[wang_qc] 未命中；排除[小李] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
