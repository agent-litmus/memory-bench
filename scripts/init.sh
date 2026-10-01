#!/usr/bin/env bash
#
# AgentLitmus · Memory Bench 初始化脚本
#
# 用途：新克隆 / 新环境下的一键初始化：
#       环境检查（JDK 17+ / Maven Wrapper / git）→ 构建打包 → 冒烟验证。
#
# 特性：可重复执行（幂等）——连续运行多次结果一致，无重复副作用：
#       - 所有检查均为「先判断再动作」或纯只读操作；
#       - 构建走 Maven 标准生命周期（clean package），天然可重跑；
#       - 不追加任何文件内容，不产生重复条目。
#
# 用法：
#   ./scripts/init.sh              # 完整初始化（检查 + 构建 + 冒烟）
#   ./scripts/init.sh --check      # 只做环境检查，不构建
#   SKIP_BUILD=1 ./scripts/init.sh # 跳过构建（其余照常）
#
# 说明：冒烟用例不依赖 API Key，离线环境也能完成初始化。

set -euo pipefail

cd "$(dirname "$0")/.."

ok()   { echo "[OK] $*"; }
warn() { echo "[!] $*" >&2; }
die()  { echo "[X] $*" >&2; exit 1; }

echo "=============================================="
echo " 1/3 环境检查"
echo "=============================================="

# ---- JDK 17+（先判断再动作，无副作用） ----
if ! command -v java >/dev/null 2>&1; then
  die "未找到 java，请先安装 JDK 17+（如：sudo apt install openjdk-17-jdk）"
fi
JAVA_VER="$(java -version 2>&1 | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
JAVA_MAJOR="${JAVA_VER%%.*}"
if [ "$JAVA_MAJOR" = "1" ]; then
  # 旧版本号风格：1.8.0_xxx → 8
  JAVA_MAJOR="${JAVA_VER#*.}"; JAVA_MAJOR="${JAVA_MAJOR%%.*}"
fi
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]]; then
  die "无法解析 JDK 版本（原始输出：$JAVA_VER）"
fi
if [ "$JAVA_MAJOR" -lt 17 ]; then
  die "JDK 版本过低（当前 ${JAVA_MAJOR}），本项目要求 17+（pom.xml: maven.compiler.release=17）"
fi
ok "JDK ${JAVA_VER}（要求 17+）"

# ---- Maven Wrapper ----
if [ ! -f ./mvnw ]; then
  die "缺少 ./mvnw，请确认在仓库根目录执行本脚本（当前：$(pwd)）"
fi
# 幂等：已可执行则 chmod 无副作用
[ -x ./mvnw ] || chmod +x ./mvnw
ok "Maven Wrapper 可用（./mvnw）"

# ---- git（可选，仅提示） ----
if command -v git >/dev/null 2>&1; then
  ok "git $(git --version | awk '{print $3}')"
else
  warn "未安装 git，仅影响提交/拉取，不影响本地构建评测"
fi

echo "目录: $(pwd)"
echo

# ---- 只做环境检查则到此为止 ----
if [ "${1:-}" = "--check" ] || [ "${SKIP_BUILD:-0}" = "1" ]; then
  echo "环境检查完成（按要求跳过构建）。"
  exit 0
fi

echo "=============================================="
echo " 2/3 构建打包（clean package -DskipTests）"
echo "=============================================="
./mvnw -q clean package -DskipTests
echo

echo "=============================================="
echo " 3/3 冒烟验证（validate-cases）"
echo "=============================================="
# 幂等：glob 取主 jar（排除 shade 插件的 original- 与 sources/javadoc），不写死版本号
JAR="$(find target -maxdepth 1 -name 'memory-bench-*.jar' ! -name 'original-*' ! -name '*sources*' ! -name '*javadoc*' | head -n1)"
[ -n "$JAR" ] || die "构建后未找到 target/memory-bench-*.jar"
echo "主程序: $JAR"
java -jar "$JAR" validate-cases
echo

echo "=============================================="
echo " 初始化完成"
echo "=============================================="
echo "下一步（均可重复执行）："
echo "  ./mvnw test -Dtest=MemoryBenchmarkRunnerTest   # 运行六维评测"
echo "  java -jar $JAR run --out out/                  # CLI 对比评测"
echo "  ./scripts/verify-openkylin.sh                  # 一键复现（环境采集+全量测试+评测）"
