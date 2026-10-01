# 用例 d13（相近区分）

## 注入事实
- 用户的备用联系方式是短信 13800002222
- 常用邮箱是 zhang@corp.com

## 提问
- 备用联系方式是什么？

## 智能体回答
您的备用联系方式是：**短信13800002222**。

另外您的常用邮箱是 zhang@corp.com。

## 判定
- Judgment[caseId=d13, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[13800002222] 命中；排除[zhang@corp.com] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
