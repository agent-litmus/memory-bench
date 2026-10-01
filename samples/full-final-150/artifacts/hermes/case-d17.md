# 用例 d17（相近区分）

## 注入事实
- 用户的签约公司是星澜科技
- 实习单位是蓝海信息

## 提问
- 我签约的公司是哪家？

## 智能体回答
您的签约公司是**星澜科技**。

（另外补充区分一下：您的实习单位是**蓝海信息**，两者是不同的。）

## 判定
- Judgment[caseId=d17, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[星澜科技] 命中；排除[蓝海信息] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
