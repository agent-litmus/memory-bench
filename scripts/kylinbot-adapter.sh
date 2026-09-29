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

if [ ! -f "$dir/config.toml" ]; then
    mkdir -p "$dir"
    cp "$HOME/.kylinbot/config.toml" "$dir/config.toml"
    cp "$HOME/.kylinbot/.secret_key" "$dir/.secret_key"
    chmod 600 "$dir/config.toml" "$dir/.secret_key"
fi

exec kylin-bot --config-dir "$dir" agent -m "$question" \
     --session-state-file "$dir/session.json"
