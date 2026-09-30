# 用例 s-u1（动态更新）

## 注入事实
- 用户当前的城市是杭州

## 更新事实
- 用户当前的城市是深圳

## 提问
- 我现在住在哪个城市？

## 智能体回答
您现在住在**深圳**。

（对话中您最初提到杭州，随后更正为深圳，以最新信息为准。）

## 判定
- Judgment[caseId=s-u1, dimension=UPDATE, passed=false, score=0.0, reason=包含[深圳] 命中；排除[杭州] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
