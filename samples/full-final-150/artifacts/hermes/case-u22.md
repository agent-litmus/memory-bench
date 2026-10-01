# 用例 u22（动态更新）

## 注入事实
- 用户负责的产线是一号产线

## 更新事实
- 用户负责的产线是三号产线

## 提问
- 我现在负责哪条产线？

## 智能体回答
您现在负责的是**三号产线**。

（本次对话中您最初说是一号产线，随后更正为三号产线，以最新的为准。）

## 判定
- Judgment[caseId=u22, dimension=UPDATE, passed=false, score=0.0, reason=包含[三号产线] 命中；排除[一号产线] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
