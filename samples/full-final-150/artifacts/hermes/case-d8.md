# 用例 d8（相近区分）

## 注入事实
- 用户上周出差去了成都
- 用户下周的出差城市是西安

## 提问
- 下周的行程城市是哪个？

## 智能体回答
下周的出差城市是**西安**。

就是您刚才告诉我的——上周去了成都，下周去西安。需要我帮您做点行程准备吗？比如临近出发时查准确天气预报、列出差清单或规划行程。

## 判定
- Judgment[caseId=d8, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[西安] 命中；排除[成都] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
