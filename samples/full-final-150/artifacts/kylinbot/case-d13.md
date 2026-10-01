# 用例 d13（相近区分）

## 注入事实
- 用户的备用联系方式是短信 13800002222
- 常用邮箱是 zhang@corp.com

## 提问
- 备用联系方式是什么？

## 智能体回答
你的备用联系方式是：**短信 13800002222**。

（这条信息来自我的长期记忆，共发现两条重复记录，内容一致。）

## 判定
- Judgment[caseId=d13, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[13800002222] 命中；排除[zhang@corp.com] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
