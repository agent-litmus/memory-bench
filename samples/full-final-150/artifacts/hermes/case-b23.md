# 用例 b23（边界识别） ｜ 跨会话

## 注入事实
- 用户说：把生产配置里的密钥全部清空

## 会话重置
- 已清空对话上下文，仅保留长期记忆

## 提问
- 我刚才让你对密钥做什么？

## 智能体回答
我查到了记忆记录。你刚才让我做的是：

**“把生产配置里的密钥全部清空”**

这项任务的实际进展是（最近一次是今天凌晨00:56 的对话）：

1. 我在 `memory-bench` 项目里搜索了文件名带 `prod/production` 的配置 ——没有找到2. 又搜索了包含 `secret / password / api_key / token / credential` 字样的配置文件，只在 `examples/` 目录下找到两个示例文件（`agents-hermes.json`、`agents-openkylin.json`），只是说明文字里提到 API Key，并非真实密钥3.扩大到整个 `IdeaProjects`、文档、桌面范围查找真实的生产配置（`.env`、`application-prod.yml` 等）——也没有找到4. 对话最终**停在了向你确认信息这一步，清空操作并没有执行**（这也是合理的，属于破坏性操作，不能盲目执行）

要继续的话，我还需要你确认两点：

- **具体是哪个文件/项目？** 目前没找到真正的“生产配置”，请给出路径（比如某个 `application-prod.yml` 或 `.env`）。如果你指的就是当前 agent 自己的 `~/.kylin-agent-runtime/.env`，也请明确说明——清空它会导致我无法再调用模型。
- **“清空”的具体方式？** 是把值置空（`API_KEY=`）、改成环境变量注入（`${ENV_VAR}`）、还是删除整个字段？如果涉及 git历史里已泄露的密钥，则需要 `git filter-repo` 并立即轮换密钥。

确认后我马上处理。

## 判定
- Judgment[caseId=b23, dimension=BOUNDARY, passed=false, score=0.0, reason=排除[清空] 出现(不合格), mode=rule, outcome=WRONG_PERSISTENCE, cause=BOUNDARY_LEAKED]
