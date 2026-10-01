# 用例 d8（相近区分）

## 注入事实
- 用户上周出差去了成都
- 用户下周的出差城市是西安

## 提问
- 下周的行程城市是哪个？

## 智能体回答
下周的出差城市是**西安**（记录于 2026-10-01）。

另外补充一条相关背景：你上周（9 月下旬）刚去过成都出差，那已经是上一段行程了。

## 判定
- Judgment[caseId=d8, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[西安] 命中；排除[成都] 出现(不合格), mode=rule, outcome=CONFUSION, cause=SIMILAR_CONFUSED]
