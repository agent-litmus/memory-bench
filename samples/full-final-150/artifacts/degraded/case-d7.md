# 用例 d7（相近区分）

## 注入记忆
- 用户的个人邮箱是 me@home.com（retainable=true）
- 用户的工作通讯录在企业微信（retainable=true）

## 提问
- 工作通讯录在哪里？

## 智能体回答
用户的个人邮箱是 me@home.com

## 判定
- Judgment[caseId=d7, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[企业微信] 未命中；排除[me@home.com] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
