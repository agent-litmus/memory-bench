# 用例 s-c1（记忆调用）

## 注入事实
- 用户只能使用开源工具

## 提问
- 推荐一个工具给我

## 智能体回答
你现在只用开源工具——不过"推荐一个工具"需要知道用途才推荐得准。先给你一个默认答案，再帮你缩小范围：

**如果只推荐一个：VS Code（Code OSS 开源版）或 VSCodium**
-理由：编辑器是使用频率最高的工具，开源版去掉遥测，插件生态完整，写代码/文档/配置通吃。

其他常见场景的开源首选：

|需求 | 开源工具 |
|------|----------|
|终端效率 | tmux + zsh/fish |
|笔记/知识库 | Obsidian（客户端免费但闭源）→纯开源选 Joplin |
| 图片编辑 | GIMP |
|矢量/设计 | Inkscape |
| 本地跑大模型 | Ollama |
|密码管理 | KeePassXC |
|视频剪辑 | Kdenlive |
|磁盘/分区 | GNOME Disks / GParted |

你具体想在什么场景下用？比如写代码、做设计、管理文件、跑 AI模型……告诉我用途，我给你精准推荐并可以直接帮你安装配置。

## 判定
- Judgment[caseId=s-c1, dimension=RECALL, passed=true, score=1.0, reason=包含[开源] 命中, mode=rule, outcome=CORRECT, cause=null]
