# 用例 r7（长期保持）

## 注入事实
- 用户的报告输出目录是 /home/user/reports

## 提问
- 报告应该输出到哪个目录？

## 智能体回答
根据记录，你的报告输出目录是：

**`/home/user/reports`**

（来自长期记忆中的核心偏好 `user_report_output_dir`。）

如果这次的报告有特殊要求，或你想临时改用别的目录（比如当前工作目录下的 `./reports`），告诉我即可。

## 判定
- Judgment[caseId=r7, dimension=RETENTION, passed=true, score=1.0, reason=包含[/home/user/reports] 命中, mode=rule, outcome=CORRECT, cause=null]
