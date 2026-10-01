# 用例 d9（相近区分）

## 注入事实
- 用户的开发服务监听在 8080
- 用户管理后台的访问入口是 9090 端口

## 提问
- 管理后台的访问入口是什么？

## 智能体回答
管理后台的访问入口是 **9090端口**（即 http://localhost:9090，如果你在本机访问；远程服务器则用对应主机 IP 加9090）。

补充说明：开发服务在8080端口，二者是分开的——8080 是业务开发服务，9090 是管理后台。

## 判定
- Judgment[caseId=d9, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[9090] 命中；排除[8080] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
