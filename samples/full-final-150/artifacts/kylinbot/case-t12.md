# 用例 t12（任务复用）

## 注入事实
- 用户常用的开发语言是 Java
- 用户习惯用 VS Code 编写代码

## 提问
- 用我常用的语言搭一个脚手架

## 智能体回答
你常用的开发语言是 **Java**（这点我记着）。工作区里已经有一个可用的 Java 脚手架了，我确认过它能正常编译、运行、测试通过：

**位置：** `java-scaffold/`，基于 **JDK 21**，纯原生 Java，无需 Maven/Gradle 即可开箱编译运行。

```
java-scaffold/
├── build.sh                # 编译 -> out/
├── run.sh                  # 编译并运行
├── test.sh                 # 编译并运行测试
├── .gitignore
└── src/
    ├── main/java/com/example/app/
    │   ├── App.java                    # 入口
    │   ├── config/AppConfig.java       # 配置（单例）
    │   ├── model/User.java             # 领域模型（record）
    │   ├── service/UserService.java    # 业务接口
    │   ├── service/impl/UserServiceImpl.java
    │   └── util/StringUtils.java       # 工具类
    └── test/java/com/example/app/
        └── AppTest.java                # 零依赖测试（内置迷你断言）
```

**已验证运行结果：**
- `./run.sh` → 正常输出 `Java Scaffold v0.1.0`
- `./test.sh` → 8 通过 / 0 失败

用的是 Java 21 特性（`record`、`switch` 模式匹配、文本块），标准目录布局，后续想迁到 Maven/Gradle 也兼容。

需要我做什么后续动作吗？比如：
1. **重新搭一个全新的**（换个包名/项目名，或改成 Maven/Gradle 版本）；
2. 在这个脚手架上**加东西**（比如加一个 `pom.xml`、接入日志框架、加 REST 层）；
3. 保持现状，就这样。

直接说哪个就行。

## 判定
- Judgment[caseId=t12, dimension=REUSE, passed=true, score=1.0, reason=包含[Java] 命中, mode=rule, outcome=CORRECT, cause=null]
