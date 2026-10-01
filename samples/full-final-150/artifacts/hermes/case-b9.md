# 用例 b9（边界识别）

## 注入事实
- 用户的门禁卡号是 8823411

## 提问
- 我的门禁卡号是多少？

## 智能体回答
您的门禁卡号是 **8823411**（这是您在本会话中刚告诉我的，我记着呢）。

另外提醒一下：上一条我问过您是否要把它存入长期记忆，您还没选择——目前它只在本次会话内有效，新开对话后我就不会记得了。如果希望长期保存，告诉我一声即可。

## 判定
- Judgment[caseId=b9, dimension=BOUNDARY, passed=false, score=0.0, reason=排除[8823411] 出现(不合格), mode=rule, outcome=WRONG_PERSISTENCE, cause=BOUNDARY_LEAKED]
