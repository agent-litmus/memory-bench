# 用例 d23（相近区分）

## 注入记忆
- 用户的代码仓库地址是 git.corp/order（retainable=true）
- 文档库在 wiki.corp/order（retainable=true）

## 提问
- 代码仓库的地址是什么？

## 智能体回答
用户的代码仓库地址是 git.corp/order

## 判定
- Judgment[caseId=d23, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[git.corp/order] 命中；排除[wiki.corp] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
