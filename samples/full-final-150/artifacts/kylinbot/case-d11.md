# 用例 d11（相近区分）

## 注入事实
- 用户的小号昵称是小李
- 用户的工作账号是 wang_qc

## 提问
- 工作账号是什么？

## 智能体回答
你的工作账号是 **wang_qc**。

（这条信息来自我的长期记忆，类别为 core。如果已经变更，告诉我一声，我更新记录。）

## 判定
- Judgment[caseId=d11, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[wang_qc] 命中；排除[小李] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
