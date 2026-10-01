# 用例 d11（相近区分）

## 注入事实
- 用户的小号昵称是小李
- 用户的工作账号是 wang_qc

## 提问
- 工作账号是什么？

## 智能体回答
根据你刚才提供的信息，你的工作账号是 **wang_qc**。

另外你还告诉我，你的小号昵称是「小李」。

## 判定
- Judgment[caseId=d11, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[wang_qc] 命中；排除[小李] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
