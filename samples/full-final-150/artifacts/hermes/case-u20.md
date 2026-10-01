# 用例 u20（动态更新）

## 注入事实
- 用户的考勤方式是打卡机

## 更新事实
- 用户的考勤方式是人脸考勤

## 提问
- 现在怎么考勤？

## 智能体回答
现在的考勤方式是**人脸考勤**。

以你本会话中最新一次告知的信息为准（你在“打卡机”之后又更新为“人脸考勤”）。如果之后方式再变，直接告诉我即可，我会始终以最新一条为准。

## 判定
- Judgment[caseId=u20, dimension=UPDATE, passed=false, score=0.0, reason=包含[人脸考勤] 命中；排除[打卡机] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
