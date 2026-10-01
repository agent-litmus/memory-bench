# 用例 t12（任务复用）

## 注入事实
- 用户常用的开发语言是 Java
- 用户习惯用 VS Code 编写代码

## 提问
- 用我常用的语言搭一个脚手架

## 智能体回答
好的——用 Java（Maven + JUnit5）搭一个标准脚手架，并附上 VS Code 配置。先看一下环境再动手。

环境情况：JDK21 已装好，但 **Maven/Gradle 都没有安装**；当前目录 `memory-bench` 已是一个现成项目，所以脚手架会建在同级目录 `~/IdeaProjects/java-scaffold`。

sudo需要密码（无法 apt 安装），但 Maven Central网络连通——直接把 Maven 解压到 `~/tools`免 root 使用。先写脚手架文件：

## 判定
- Judgment[caseId=t12, dimension=REUSE, passed=true, score=1.0, reason=包含[Java] 命中, mode=rule, outcome=CORRECT, cause=null]
