# 用例 d12（相近区分）

## 注入事实
- 用户的测试库地址是 test.example.com
- 用户生产环境的证书有效期到 2027 年

## 提问
- 生产环境的证书有效期到什么时候？

## 智能体回答
根据记录，你的生产环境 TLS 证书有效期到 **2027 年**，但具体到期日期之前没有提供，因此我这边没有记录确切的月份/日期。

如果你需要精确的到期日，可以：
- 提供证书文件（.crt/.pem），我帮你用 `openssl x509 -enddate -noout -in cert.pem` 解析；
- 或直接告诉我准确日期，我更新到记忆里。

要现在补充具体日期吗？

## 判定
- Judgment[caseId=d12, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[2027] 命中；排除[test.example.com] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
