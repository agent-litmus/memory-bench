# 用例 c24（记忆调用）

## 注入事实
- 用户要求所有脚本必须可重复执行

## 提问
- 帮我写个初始化脚本

## 智能体回答
工作区里目前是空的（只有 `SOUL.md`、`IDENTITY.md` 和 memory/sessions 数据），没有现成的项目代码，所以我不确定这个「初始化脚本」要针对什么环境。为了不瞎猜写错，想先确认几点：

1. **给什么用？** 比如：
   - 新机器/服务器的环境初始化（装常用软件、配置 shell、SSH、时区等）
   - 某个具体项目的开发环境初始化（Python/Node/Go 等依赖、虚拟环境、`.env`）
   - 数据库建表/种子数据初始化
   - Docker / Docker Compose 一键拉起环境

2. **跑在哪个系统上？** Linux（发行版？）、macOS，还是 Windows。

3. **语言/技术栈和具体要做的事**，例如：包管理器（apt/yum/brew）、需要装哪些组件、要不要幂等（重复执行安全）。

你只要告诉我「给 X 项目在 Y 系统上做 Z」这几个信息，我就直接写好脚本给你。如果暂时没想好，我也可以先按最常见的场景——**Linux 上初始化 Python 项目开发环境（含幂等检查）**——给个模板，你再改。要哪种？

## 判定
- Judgment[caseId=c24, dimension=RECALL, passed=false, score=0.0, reason=包含[可重复执行] 未命中, mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
