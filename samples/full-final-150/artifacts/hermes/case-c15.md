# 用例 c15（记忆调用）

## 注入事实
- 用户要求所有金额保留两位小数

## 提问
- 这笔费用要保留几位小数？

## 智能体回答
根据您之前的要求，这笔费用要**保留两位小数**。

例如：计算结果为1234.567 元时，会按四舍五入呈现为 **¥1,234.57**。

有具体数据需要计算的话，直接发给我即可。

## 判定
- Judgment[caseId=c15, dimension=RECALL, passed=true, score=1.0, reason=包含[两位小数] 命中, mode=rule, outcome=CORRECT, cause=null]
