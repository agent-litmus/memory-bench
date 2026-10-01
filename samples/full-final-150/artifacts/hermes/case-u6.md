# 用例 u6（动态更新）

## 注入事实
- 用户使用的界面主题是深色主题

## 更新事实
- 用户使用的界面主题是浅色主题

## 提问
- 我现在用的什么主题？

## 智能体回答
你现在用的是**浅色主题**。

（你最初提到是深色主题，随后更正为浅色主题，以最新的说法为准。）

## 判定
- Judgment[caseId=u6, dimension=UPDATE, passed=false, score=0.0, reason=包含[浅色] 命中；排除[深色] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
