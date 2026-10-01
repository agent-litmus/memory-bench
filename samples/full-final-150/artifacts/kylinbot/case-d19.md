# 用例 d19（相近区分）

## 注入事实
- 用户的默认打印机是三楼东侧那台
- 扫描仪在一楼西侧

## 提问
- 默认打印机是哪台？

## 智能体回答
这台机器上没有配置任何打印机，因此也没有默认打印机。

检测结果：
- `lpstat -d` → **无系统默认目标**（即未设置默认打印机）
- `lpstat -p` → **未添加目标**（CUPS 中没有任何已安装打印机）
- 环境变量中也没有 `PRINTER` / `LPDEST` 之类的指定

系统里只装了 CUPS 客户端工具（`lpstat` 等），但服务端没有任何打印队列。

如果你确实需要让某台打印机成为默认，可以先添加队列再指定，例如：
```bash
lpadmin -p <打印机名> -E -v <连接URI> -m <驱动PPD>
lpoptions -d <打印机名>      # 设为默认
```
告诉我打印机型号/连接方式（USB、网络 IP 等），我可以帮你具体配置。

## 判定
- Judgment[caseId=d19, dimension=DISCRIMINATION, passed=false, score=0.0, reason=包含[三楼东侧] 未命中；排除[一楼西侧] 未出现(合格), mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
