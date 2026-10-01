# 用例 t19（任务复用）

## 注入记忆
- 用户的邮件签名固定为【张伟 | 质检部】（retainable=true）
- 用户要申请下周调休（retainable=true）

## 提问
- 帮我写一封调休申请邮件

## 智能体回答
用户的邮件签名固定为【张伟 | 质检部】

## 判定
- Judgment[caseId=t19, dimension=REUSE, passed=true, score=1.0, reason=包含[质检部] 命中, mode=rule, outcome=CORRECT, cause=null]
