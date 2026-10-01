# 用例 d9（相近区分）

## 注入事实
- 用户的开发服务监听在 8080
- 用户管理后台的访问入口是 9090 端口

## 提问
- 管理后台的访问入口是什么？

## 智能体回答
管理后台的访问入口是 **9090 端口**。

（依据记忆库中的核心条目，两条记录一致：`user_admin_backend_port` 和 `admin_console_port` 均为 9090。）

## 判定
- Judgment[caseId=d9, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[9090] 命中；排除[8080] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
