# 用例 d19（相近区分）

## 注入记忆
- 用户的默认打印机是三楼东侧那台（retainable=true）
- 扫描仪在一楼西侧（retainable=true）

## 提问
- 默认打印机是哪台？

## 智能体回答
用户的默认打印机是三楼东侧那台

## 判定
- Judgment[caseId=d19, dimension=DISCRIMINATION, passed=true, score=1.0, reason=包含[三楼东侧] 命中；排除[一楼西侧] 未出现(合格), mode=rule, outcome=CORRECT, cause=null]
