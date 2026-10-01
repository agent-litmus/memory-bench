# 用例 c1（记忆调用）

## 注入事实
- 用户只能使用开源工具

## 提问
- 推荐一个工具给我

## 智能体回答
好的，遵守"只用开源工具"这个前提。不过"工具"范围太广了——为了不推荐偏，能说下你想用来做什么吗？

举几个常见方向的开源例子，方便你定位：

- **笔记/知识管理**：Obsidian（免费）、Joplin、Logseq- **编辑器/IDE**：VS Code（开源版 VSCodium）、Zed、Neovim- **办公**：LibreOffice- **图片处理**：GIMP、Inkscape- **文件同步/网盘**：Syncthing、Nextcloud- **开发辅助**：Git、Docker、Postman替代品 Bruno/Hoppscotch直接回我一个用途（比如"想找免费的本地笔记软件"），我给你具体推荐一个 +理由和上手方式。

等待你的补充：缺少只有用户能提供的关键信息——工具的用途/领域；候选回复把“推荐一个具体工具+理由”这一核心交付推迟到用户说明用途之后，是对未来行动的承诺而非已完成的结果报告。

- 向用户提出唯一关键问题：这个工具打算用来做什么（如记笔记、写代码、修图、文件同步），以便给出单一推荐+理由+上手方式。
- 若用户仍不说明用途，可直接给出一个通用默认推荐（如 VSCodium 或 Joplin）并附理由与上手步骤，同时说明可按用途更换推荐。
- 可顺带确认开源许可偏好（是否需 OSI 认证/商用友好如 MIT/Apache-2.0，是否排除 AGPL/GPL）。

## 判定
- Judgment[caseId=c1, dimension=RECALL, passed=true, score=1.0, reason=包含[开源] 命中, mode=rule, outcome=CORRECT, cause=null]
