# 用例 t5（任务复用）

## 注入事实
- 用户的报告输出目录是 /home/user/reports
- 用户要求所有报告都写到该目录

## 提问
- 把这份报告写到我常用的位置

## 智能体回答
我记得你常用的位置是 `/home/user/reports`，但这次消息里没有附上任何报告——工作区里也没有可用的报告文件（只有一个空的 workspace、配置和记忆数据库）。

所以我暂时没法落笔。请给我以下任一项，我就直接写到 `/home/user/reports`：

- 报告的正文内容，或者
- 报告文件（路径 / 上传），或者
- 如果是让我基于某个源生成报告，请指明数据来源。

另外确认一下：文件名和格式（如 `report_2026-10-01.md` / `.docx` / `.pdf`）有偏好吗？

（说明：我没有凭空捏造一份报告来"完成"这个请求——"这份报告"必须来自你提供的实际内容。）

## 判定
- Judgment[caseId=t5, dimension=REUSE, passed=true, score=1.0, reason=包含[/home/user/reports] 命中, mode=rule, outcome=CORRECT, cause=null]
