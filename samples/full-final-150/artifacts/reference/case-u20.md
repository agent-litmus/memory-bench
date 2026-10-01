# 用例 u20（动态更新）

## 注入记忆
- 用户的考勤方式是打卡机（retainable=true）

## 更新记忆
- 用户的考勤方式是人脸考勤

## 提问
- 现在怎么考勤？

## 智能体回答
用户的考勤方式是人脸考勤

## 判定
- Judgment[caseId=u20, dimension=UPDATE, passed=true, score=1.0, reason=包含[人脸考勤] 命中；排除[打卡机] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
