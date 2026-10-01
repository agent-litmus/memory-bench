# 用例 u17（动态更新）

## 注入事实
- 用户使用的 JDK 版本是 8

## 更新事实
- 用户使用的 JDK 版本是 17

## 提问
- 我用的 JDK 是哪个版本？

## 智能体回答
你当前环境里的默认 JDK 是 **OpenJDK 21**：

- `java -version` → `openjdk 21.0.11-ea`（build `21.0.11-ea+8-openKylin-ok1`，openKylin 定制版）
- `javac -version` → `21.0.11-ea`
- `java` 实际指向 `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`

另外补充两点：

1. 系统里**同时装了 JDK 17**（`/usr/lib/jvm/java-17-openjdk-amd64`），`update-alternatives --list java` 能看到 17 和 21 两个可选版本，当前默认指向 21。
2. `JAVA_HOME` 未设置，也没有 Maven / Gradle 项目文件，所以「你用的是哪个版本」只能按命令行默认的 21 来判定。

如果你指的是某个具体项目的编译/运行版本，告诉我项目路径或 `pom.xml` / `build.gradle`，我再帮你确认项目实际锁定的 JDK 版本（那可能和系统默认的不一致）。

## 判定
- Judgment[caseId=u17, dimension=UPDATE, passed=false, score=0.0, reason=包含[17] 命中；排除[8] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
