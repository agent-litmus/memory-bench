# 用例 d4（相近区分）

## 注入事实
- 用户的测试密钥是 sk-test-001
- 用户的生产账号是 prod_admin

## 提问
- 生产账号是什么？

## 智能体回答
生产账号是 **`prod_admin`**。

另外本次会话中你还提供过测试密钥：`sk-test-001`。

需要我基于这些凭证做什么操作吗？

## 判定
- Judgment[caseId=d4, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[prod_admin] 命中；排除[sk-test-001] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
