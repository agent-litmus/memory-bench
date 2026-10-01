# 用例 u4（动态更新）

## 注入事实
- 用户当前的城市是杭州

## 更新事实
- 用户当前的城市是深圳

## 提问
- 我现在住在哪个城市？

## 智能体回答
您现在所在的城市是**深圳**。

（您先是说在杭州，随后更正为深圳，以最新的为准。）

## 判定
- Judgment[caseId=u4, dimension=UPDATE, passed=false, score=0.0, reason=包含[深圳] 命中；排除[杭州] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
