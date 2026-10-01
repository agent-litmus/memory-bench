# 用例 r21（长期保持） ｜ 跨会话

## 注入事实
- 用户的备用邮箱是 backup@starlan.com

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我的备用邮箱是什么？

## 智能体回答
Your backup email is **backup@starlan.com**.

## 判定
- Judgment[caseId=r21, dimension=RETENTION, passed=true, score=1.0, reason=包含[backup@starlan.com] 命中, mode=rule, outcome=CORRECT, cause=null]
