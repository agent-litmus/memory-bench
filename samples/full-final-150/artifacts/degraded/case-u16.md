# 用例 u16（动态更新）

## 注入记忆
- 用户的直属领导是孙经理（retainable=true）

## 更新记忆
- 用户的直属领导是周经理

## 提问
- 我的直属领导是谁？

## 智能体回答
用户的直属领导是孙经理

## 判定
- Judgment[caseId=u16, dimension=UPDATE, passed=false, score=0.0, reason=包含[周经理] 未命中；排除[孙经理] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
