# 用例 d12（相近区分）

## 注入记忆
- 用户的测试库地址是 test.example.com（retainable=true）
- 用户生产环境的证书有效期到 2027 年（retainable=true）

## 提问
- 生产环境的证书有效期到什么时候？

## 智能体回答
用户的测试库地址是 test.example.com

## 判定
- Judgment[caseId=d12, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[2027] 未命中；排除[test.example.com] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
