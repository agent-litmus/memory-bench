# 用例 t17（任务复用）

## 注入事实
- 用户常用的开发语言是 Java
- 用户偏好命令行工具

## 提问
- 用我常用的开发语言写一个命令行脚手架

## 智能体回答
Found a real defect: the generated `run.sh` isn't executable, so the documented `./run.sh` step fails. Fixing it:Found a real defect: the generated `run.sh` isn't executable, so the documented `./run.sh` step fails. Fixing it:你的常用语言是 **Java**，脚手架已经建好并跑通了 —— 项目在 `java-cli-scaffold/`，零第三方依赖（单文件 `ArgParser` 代替 picocli，模板内联在代码里），JDK 21 直接编译运行。

## 用法

```bash
./bin/scaffold new my-app --template maven --package com.acme.myapp --author kylin
./bin/scaffold new tool --template plain --output ~/projects
./bin/scaffold list
./bin/scaffold help new
```

## 结构

```
java-cli-scaffold/
├── bin/scaffold                  # 启动脚本，源码有改动时自动增量编译
├── pom.xml                       # 可选 Maven 打包
└── src/main/java/com/kylin/cli/
    ├── Main.java                 # 入口，注册子命令
    ├── core/  Cli · Command · Context · ArgParser
    ├── command/  New · List · Help
    ├── gen/Templates.java        # maven / plain 两套内置模板
    └── util/  Terminal · Template · Files2
```

- 命令接口 `Command`（`name/summary/usage/run`），加子命令只要实现它再 `cli.register(...)`。
- `{{name}} {{package}} {{packagePath}} {{author}} {{year}}` 占位符渲染，路径和内容都能用。
- 退出码约定：`0` 成功 / `1` 运行失败 / `2` 用法错误；`Terminal` 在重定向、管道下自动去色。

## 实测结果

| 场景 | 结果 |
|---|---|
| `--help` / `list` / `--version` | 正常，退出码 0 |
| `new demo-app --template maven` | 生成 5 个文件，路径按包名展开 |
| `new tool --template plain` → `./run.sh` | 输出 `Hello from tool` |
| 重复生成同一目录 | 报错并提示 `--force`，退出码 2 |
| `new`（缺项目名）、未知命令 | 报错 + 用法提示，退出码 2 |

**顺手修了一个真 bug：**`plain` 模板生成的 `run.sh` 没有可执行位，README 里写的 `./run.sh` 会 `Permission denied`。已在 `Files2.write` 里对 `.sh`/`.bash` 补上 `+x`（非 POSIX 平台静默跳过）。现在 `-rwxrwxr-x run.sh`，`./run.sh` 直接可用。

用 `mvn -q package` 打包需要本机有 Maven（当前环境没装，我走的是 `./bin/scaffold` 直编路径，不影响使用）。

## 判定
- Judgment[caseId=t17, dimension=REUSE, passed=true, score=1.0, reason=包含[Java] 命中, mode=rule, outcome=CORRECT, cause=null]
