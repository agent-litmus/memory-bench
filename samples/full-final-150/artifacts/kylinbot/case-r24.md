# 用例 r24（长期保持） ｜ 跨会话

## 注入事实
- 用户的健身习惯是每周游泳两次

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我的健身习惯是什么？

## 智能体回答
根据我的长期记忆，你的健身习惯是**每周游泳两次**。

这条记录保存在 core（永久）记忆里，key 为 `user_fitness_habit`。如果你想更新（比如频率、项目有变化），告诉我，我会同步修改。

## 判定
- Judgment[caseId=r24, dimension=RETENTION, passed=true, score=1.0, reason=包含[游泳] 命中, mode=rule, outcome=CORRECT, cause=null]
