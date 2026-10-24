#!/usr/bin/env bash
#
# 容器格子的可复现实测（对应 docs/USAGE.md §8 的平台矩阵）。
#
# 这一格要证明的不是「jar 能在容器里启动」，而是四件更硬的事：
#   1) 无桌面（无 DISPLAY）、无 bwrap、无 git、root 账户下，run/selftest/verify 全链路跑通；
#   2) 命令行型被测在容器里仍能接入：沙箱退化为无特权隔离，相对路径与 sandboxSeed 都照常工作；
#   3) 要求 sandbox=strict 而容器里没有 bwrap 时必须直接失败，不静默降级；
#   4) 跨 JVM（17 与 21）产出的 report.txt 逐字节一致——「编译目标 17，17+ 均可运行」不是纸面声明。
#
# 用法：
#   ./scripts/verify-container.sh            # 需要 docker 与网络（拉取两个 JRE 镜像）
#   LITMUS_IMAGE_21=... LITMUS_IMAGE_17=... ./scripts/verify-container.sh
set -euo pipefail

IMAGE_21="${LITMUS_IMAGE_21:-eclipse-temurin:21-jre}"
IMAGE_17="${LITMUS_IMAGE_17:-eclipse-temurin:17-jre}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
cleanup() {
  # 容器内以 root 产出的文件属主是 root，宿主删不掉：先用一个容器把属主改回来
  docker run --rm -v "$WORK":/work "$IMAGE_21" \
      chown -R "$(id -u):$(id -g)" /work >/dev/null 2>&1 || true
  rm -rf "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || { echo "需要 docker（或设 LITMUS_IMAGE_* 用别的容器运行时改跑）" >&2; exit 1; }

JAR="${LITMUS_JAR:-}"
if [ -z "$JAR" ]; then
  JAR="$(ls -1 "$REPO"/target/memory-bench-*.jar 2>/dev/null | grep -v original- | head -1 || true)"
fi
if [ -z "$JAR" ]; then
  echo "==> 未找到可执行 jar，先构建"
  (cd "$REPO" && ./mvnw -q clean package -DskipTests \
      -Dlitmus.git.commit="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)")
  JAR="$(ls -1 "$REPO"/target/memory-bench-*.jar | grep -v original- | head -1)"
fi
echo "==> 使用 jar: $JAR"

# 一个最小的命令行型被测：验证沙箱注入与相对路径在容器里也成立
mkdir -p "$WORK/scripts"
cat > "$WORK/scripts/echo-agent.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
session="${1:-default}"; question="${2:-}"
dir="$HOME/agent/$session"
mkdir -p "$dir"
if [ "$question" = "--reset" ]; then rm -f "$dir/session.txt"; exit 0; fi
echo "$question" >> "$dir/session.txt"
[ -f "$HOME/seed.txt" ] && echo "注入文件可读: $(cat "$HOME/seed.txt")"
echo "回答：$question"
EOF
chmod +x "$WORK/scripts/echo-agent.sh"
echo "凭据占位（不是真凭据）" > "$WORK/seed.txt"
cat > "$WORK/agents.json" <<EOF
[{"id":"echo-cli","name":"回声 CLI","type":"command","command":"bash",
  "args":["/work/scripts/echo-agent.sh","{sessionId}","{question}"],
  "resetArgs":["/work/scripts/echo-agent.sh","{sessionId}","--reset"],
  "timeoutSeconds":30,"sandboxSeed":["/work/seed.txt"]}]
EOF
cat > "$WORK/agents-strict.json" <<EOF
[{"id":"strict-cli","name":"要求内核级隔离","type":"command","command":"bash",
  "args":["/work/scripts/echo-agent.sh","{sessionId}","{question}"],"sandbox":"strict"}]
EOF
cat > "$WORK/agents-missing-seed.json" <<EOF
[{"id":"missing-seed","name":"注入源缺失","type":"command","command":"bash",
  "args":["/work/scripts/echo-agent.sh","{sessionId}","{question}"],
  "sandboxSeed":["/work/根本没有这个文件.key"]}]
EOF

run_in() {  # run_in <image> <out-subdir> <script>
  local image="$1" out="$2" script="$3"
  docker run --rm \
    -v "$REPO":/repo:ro -v "$JAR":/jar/litmus.jar:ro -v "$WORK":/work -v "$WORK/out":/out \
    "$image" bash -c "$script" > "$WORK/out/$out.log" 2>&1
}

mkdir -p "$WORK/out"

for pair in "21:$IMAGE_21" "17:$IMAGE_17"; do
  tag="${pair%%:*}"; image="${pair#*:}"
  echo "==> JDK $tag（$image）"
  docker pull -q "$image" >/dev/null

  run_in "$image" "jdk$tag" '
    set -e
    java -version 2>&1 | head -1
    echo "bwrap: $(command -v bwrap || echo 无)｜git: $(command -v git || echo 无)｜DISPLAY: ${DISPLAY:-无}｜uid: $(id -u)"
    java -jar /jar/litmus.jar run --suite full --agents reference,degraded --out /out/jdk'"$tag"'-base >/dev/null
    java -jar /jar/litmus.jar selftest --out /out/jdk'"$tag"'-selftest >/dev/null
    java -jar /jar/litmus.jar verify --from /out/jdk'"$tag"'-base | tail -2
  '
  grep -q "结论       : 通过" "$WORK/out/jdk$tag.log" || { cat "$WORK/out/jdk$tag.log"; echo "✗ JDK $tag 契约对账未通过" >&2; exit 1; }
  echo "    run + selftest + verify 通过"

  # 命令行型被测：容器里没有 bwrap，沙箱必须退化成无特权隔离而不是失败。
  # 这里不能在容器里就把输出过滤成只剩「覆盖率」那一行——后面还要从同一份日志里
  # 断言归因证据化的 basis，过滤掉就等于让断言永远失败（而且失败信息看起来像被测坏了）。
  # 顺带：这份日志是容器里的**控制台输出**，而 temurin 镜像默认没有 LANG，
  # 所以 grep 中文能命中本身就证明了 POSIX locale 下的 UTF-8 兜底生效（见 PITFALLS §8.6）
  run_in "$image" "jdk$tag-cli" '
    java -jar /jar/litmus.jar run --cases /repo/examples/cases-demo-4.json \
         --agents-config /work/agents.json --out /out/jdk'"$tag"'-cli
  '
  grep -q "覆盖率(已判定/计划): 100.0%" "$WORK/out/jdk$tag-cli.log" \
    || { cat "$WORK/out/jdk$tag-cli.log"; echo "✗ JDK $tag 命令行接入出现不可判，说明沙箱或注入在容器里失效" >&2; exit 1; }
  echo "    命令行型被测接入通过（无 bwrap，无特权隔离 + sandboxSeed）"

  # 命令行型被测的记忆在它自己进程里，评测方读不到：归因证据化必须如实承认这一点，
  # 而不是把「从回答文本推断出来的归因」报成观测事实。
  grep -q "记忆快照不可读" "$WORK/out/jdk$tag-cli.log" \
    || { cat "$WORK/out/jdk$tag-cli.log"; echo "✗ 命令行型被测被当成了记忆可读——归因证据化会虚高" >&2; exit 1; }
  echo "    归因证据化如实记为「记忆快照不可读」（归因是推断，不是观测）"

  # 内置被测是记忆注入模式，记忆逐条可读，比例必须是 1.0——掉下来就是判据或证据链断了
  python3 - "$WORK/out/jdk$tag-base/attribution.json" <<'ATTR'
import json, sys
d = json.load(open(sys.argv[1]))
assert d["meetsTarget"], "内置被测归因证据化 %.3f < %.2f" % (d["minRatio"], d["target"])
for a in d["agents"]:
    assert "记忆快照可读" in a["basis"], a["basis"]
print("    归因证据化达标：%s" % ", ".join(
    "%s %d/%d" % (a["agent"], a["backed"], a["attributed"]) for a in d["agents"]))
ATTR
done

echo "==> 跨 JVM 一致性"
H21="$(sha256sum "$WORK/out/jdk21-base/report.txt" | cut -d' ' -f1)"
H17="$(sha256sum "$WORK/out/jdk17-base/report.txt" | cut -d' ' -f1)"
[ "$H21" = "$H17" ] || { echo "✗ JDK 17 与 21 的 report.txt 不一致：$H17 vs $H21" >&2; exit 1; }
echo "    report.txt sha256 一致：${H21:0:16}…"

echo "==> sandbox=strict 在缺 bwrap 时必须直接失败"
run_in "$IMAGE_21" "strict" '
  java -jar /jar/litmus.jar run --cases /repo/examples/cases-demo-4.json \
       --agents-config /work/agents-strict.json --out /out/strict; echo "退出码=$?"
' || true
grep -q "退出码=1" "$WORK/out/strict.log" \
  || { cat "$WORK/out/strict.log"; echo "✗ 要求内核级隔离却没失败——这是静默降级，属于安全问题" >&2; exit 1; }
grep -q "未找到 bwrap 或 sandbox-exec" "$WORK/out/strict.log" \
  || { echo "✗ 失败原因没写清楚" >&2; exit 1; }
echo "    直接失败且写明原因（未静默降级）"

echo "==> 注入源缺失必须在契约里留痕"
run_in "$IMAGE_21" "missing-seed" '
  java -jar /jar/litmus.jar run --cases /repo/examples/cases-demo-4.json \
       --agents-config /work/agents-missing-seed.json --out /out/missing-seed >/dev/null 2>&1
  grep -o "注入未完成 1 项[^\"]*" /out/missing-seed/manifest.json | head -1
'
grep -q "注入未完成 1 项" "$WORK/out/missing-seed.log" \
  || { cat "$WORK/out/missing-seed.log"; echo "✗ 注入失败没有披露——接入者只会看到「无响应」" >&2; exit 1; }
echo "    已披露"

echo
echo "容器格子实测通过：$IMAGE_21 与 $IMAGE_17（无桌面 / 无 bwrap / 无 git / root）"
echo "  ├─ run --suite full（283 条）+ selftest + verify 全链路"
echo "  ├─ 命令行型被测接入（无特权隔离 + sandboxSeed）"
echo "  ├─ 跨 JVM report.txt 逐字节一致"
echo "  ├─ sandbox=strict 缺 bwrap 时直接失败，不静默降级"
echo "  └─ 注入源缺失在运行契约里留痕"
