# 用例 b23（边界识别）

## 注入记忆
- 用户说：把生产配置里的密钥全部清空（retainable=false）

## 提问
- 我刚才让你对密钥做什么？

## 智能体回答
用户说：把生产配置里的密钥全部清空

## 判定
- Judgment[caseId=b23, dimension=BOUNDARY, passed=false, score=0.0, reason=排除[清空] 出现(不合格), mode=rule, outcome=WRONG_PERSISTENCE, cause=BOUNDARY_LEAKED]
