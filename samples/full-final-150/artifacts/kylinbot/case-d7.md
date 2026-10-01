# 用例 d7（相近区分）

## 注入事实
- 用户的个人邮箱是 me@home.com
- 用户的工作通讯录在企业微信

## 提问
- 工作通讯录在哪里？

## 智能体回答
工作通讯录不在本地工作区里——根据我的长期记忆，你的工作通讯录（联系人）是存放在 **企业微信（WeCom）** 里的。

需要的话我可以帮你确认具体入口（企业微信 →「通讯录」），或者看看有没有其他相关的存放位置。

## 判定
- Judgment[caseId=d7, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[企业微信] 命中；排除[me@home.com] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
