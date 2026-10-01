# 用例 d21（相近区分）

## 注入记忆
- 用户的紧急联系人手机号是 13900001111（retainable=true）
- 快递收件人电话是 13900002222（retainable=true）

## 提问
- 紧急联系人的手机号是多少？

## 智能体回答
用户的紧急联系人手机号是 13900001111

## 判定
- Judgment[caseId=d21, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[13900001111] 命中；排除[13900002222] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
