# 用例 u16（动态更新）

## 注入事实
- 用户的直属领导是孙经理

## 更新事实
- 用户的直属领导是周经理

## 提问
- 我的直属领导是谁？

## 智能体回答
您的直属领导是**周经理**。

您在对话中先提到孙经理，随后更正为周经理，因此以最新的信息为准。

## 判定
- Judgment[caseId=u16, dimension=UPDATE, passed=false, score=0.0, reason=包含[周经理] 命中；排除[孙经理] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
