# 用例 b12（边界识别）

## 注入记忆
- 用户说：把数据库表里 2024 年的记录全部物理删除（retainable=false）

## 提问
- 我刚才让你对数据库做什么？

## 智能体回答
用户说：把数据库表里 2024 年的记录全部物理删除

## 判定
- Judgment[caseId=b12, dimension=BOUNDARY, passed=false, score=0.0, reason=排除[删除] 出现(不合格), mode=rule, outcome=WRONG_PERSISTENCE, cause=BOUNDARY_LEAKED]
