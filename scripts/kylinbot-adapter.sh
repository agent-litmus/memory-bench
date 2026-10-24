#!/usr/bin/env bash
#
# KylinBot 适配器（供 memory-bench 的 command 类型智能体调用）
#
# 用法：
#   scripts/kylinbot-adapter.sh <sessionId> <question>
#
# 作用：
#   1) 为每个评测会话建立独立的 KylinBot 工作区（--config-dir），
#      使各用例之间的长期记忆互不污染，且不触碰用户真实的 ~/.kylinbot 记忆；
#   2) 用 --session-state-file 把多轮对话状态落盘，
#      保证 memory-bench 多次独立调用之间仍是同一段会话。
#
# 说明：隔离目录需要 config.toml 与 .secret_key 成对拷贝，
#       否则 api_key 的 enc2 解密会失败。
#
set -euo pipefail

session="${1:-default}"
question="${2:-}"

base="${LITMUS_KYLINBOT_HOME:-/tmp/litmus-kylinbot}"
dir="$base/$session"

# 重置模式：删除会话状态文件（对话历史），保留长期记忆库 brain.db
# ——用于跨会话长期保持用例：答对只能来自长期记忆，不能靠上下文窗口
if [ "$question" = "--reset" ]; then
    rm -f "$dir/session.json"
    exit 0
fi

# 会话工作区首次使用时建立：拷入成对凭据（缺 .secret_key 会导致 api_key 解密失败）
if [ ! -f "$dir/config.toml" ] || [ ! -f "$dir/.secret_key" ]; then
    mkdir -p "$dir"
    # 凭据来源两档：真实主目录下的 ~/.kylinbot（手工运行），
    # 或 memory-bench 注入到沙箱家目录根部的同名文件（评测运行，见 agents 配置的 sandboxSeed）。
    # 必须**成对**才算候选目录：kylin-bot 运行时会自己在 ~/$HOME/.kylinbot 下生成一份
    # config.toml（不含 .secret_key），一旦它出现就按「有 config.toml 就用它」挑，
    # 拷 .secret_key 必然失败——表现是此后每个新会话第一条命令就退出码 1（约 8ms，
    # 根本没走到 kylin-bot），报告里只留下「无响应」。
    seed=""
    for cand in "$HOME/.kylinbot" "$HOME"; do
        if [ -f "$cand/config.toml" ] && [ -f "$cand/.secret_key" ]; then
            seed="$cand"
            break
        fi
    done
    if [ -z "$seed" ]; then
        echo "kylinbot-adapter: 找不到成对凭据（config.toml 与 .secret_key 需同时存在），已尝试 $HOME/.kylinbot 与 $HOME" >&2
        exit 3
    fi
    # 两份都拷成功才一起落定：中途失败时不能留下「有 config.toml、没 .secret_key」的半成品，
    # 否则下一次进入本分支的条件虽然仍会重试，但任何只检查 config.toml 的写法都会把它当成已就绪
    tmp="$(mktemp -d "$dir/.provision.XXXXXX")"
    cp "$seed/config.toml" "$tmp/config.toml"
    cp "$seed/.secret_key" "$tmp/.secret_key"
    mv "$tmp/config.toml" "$dir/config.toml"
    mv "$tmp/.secret_key" "$dir/.secret_key"
    rmdir "$tmp"
    chmod 600 "$dir/config.toml" "$dir/.secret_key"
fi

exec kylin-bot --config-dir "$dir" agent -m "$question" \
     --session-state-file "$dir/session.json"
