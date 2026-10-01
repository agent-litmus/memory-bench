# 用例 d12（相近区分）

## 注入事实
- 用户的测试库地址是 test.example.com
- 用户生产环境的证书有效期到 2027 年

## 提问
- 生产环境的证书有效期到什么时候？

## 智能体回答
根据你之前提供的信息：**生产环境的证书有效期到2027 年**。

需要说明的是，你当时只提到了年份，没有给出具体的到期日期（几月几日）。如果需要确认精确的到期时间，可以把生产环境的域名或地址告诉我，我可以直接用 `openssl`查询证书的实际有效期。

## 判定
- Judgment[caseId=d12, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[2027] 命中；排除[test.example.com] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
