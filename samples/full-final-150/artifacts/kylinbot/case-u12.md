# 用例 u12（动态更新）

## 注入事实
- 用户的默认分支是 master

## 更新事实
- 用户的默认分支是 main

## 提问
- 默认分支是哪个？

## 智能体回答
默认分支这件事，我这边**内存里有两条互相冲突的记录**，而且实际情况也因仓库而异，所以先如实说明，不敢直接给你一个"确定"答案：

**1. 长期记忆冲突**
- `git_default_branch`（较新，23:22 写入）：*"用户的默认分支是 master（不是 main）。涉及 git 操作时以 master 为准。"*
- `user_default_branch`（较早，22:23 写入）：*"The user's default Git branch is 'main'."*

第二条明确说自己是在纠正第一条，但它俩谁也没覆盖谁，仍同时存在。

**2. 你机器上真实仓库的情况（刚核对）**

| 仓库 | 当前分支 / 远端默认 |
|---|---|
| `~/IdeaProjects/memory-bench` | `main`（origin/HEAD → main） |
| `~/IdeaProjects/ai-augmented-employer-toolkit` | `main`（github/HEAD → main） |
| `~/RustroverProjects/untitled` | `master` |
| `~/.kylin-agent-runtime/agent-runtime` | `master`（origin/HEAD → master） |

另外全局没有设置 `init.defaultBranch`，新仓库的分支名取决于 git 版本默认值（新版为 `master`，git ≥ 2.28 可配置）。

**结论**：没有单一的"默认分支"——你两个主力项目在 `main` 上，两个在 `master` 上；记忆里则是"新旧记录打架"。

想怎么处理？请挑一个，我照办：
1. **以 master 为准**（保留较新的纠正记录，删掉 `user_default_branch`）；
2. **以 main 为准**（删掉 `git_default_branch`）；
3. **按仓库判断**（不设全局默认，每条 git 操作先看该仓库）——这也是目前事实上的做法；
4. 或者你把"默认分支"具体指哪个仓库，我直接给你那个仓库的答案。

## 判定
- Judgment[caseId=u12, dimension=UPDATE, passed=false, score=0.0, reason=包含[main] 命中；排除[master] 出现(不合格), mode=rule, outcome=WRONG_REUSE, cause=STALE_REUSE]
