#!/usr/bin/env bash
#
# 打包跨平台自包含可执行（app-image：内置精简运行时，无需系统 Java）
#
# 产物：dist/agent-litmus-<os>-<arch>.tar.gz
#   os   = macos / linux
#   arch = arm64 / x64
#
# 用法（macOS / Linux）：
#   ./scripts/build-binary.sh              # 构建 jar + 打包
#   LITMUS_JAR=target/memory-bench-1.0.0.jar ./scripts/build-binary.sh   # 复用已有 jar
#
# Windows 用 scripts/build-binary.ps1。
# CI 在 Release 发布时按 5 平台矩阵调用（见 .github/workflows/ci.yml 的 binaries job），
# pip 薄壳（python/）按同样的文件名下载。
#
set -euo pipefail

cd "$(dirname "$0")/.."

APP_NAME="agent-litmus"
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)"
if [ -z "$VERSION" ]; then
  echo "无法从 pom.xml 读出版本号" >&2
  exit 1
fi
MAIN_JAR="memory-bench-${VERSION}.jar"

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"          # darwin / linux
case "$OS" in
  darwin) PLAT="macos" ;;
  linux)  PLAT="linux" ;;
  *) echo "不支持的系统: $OS（Windows 请用 scripts/build-binary.ps1）" >&2; exit 1 ;;
esac
MACHINE="$(uname -m)"
case "$MACHINE" in
  arm64|aarch64) ARCH="arm64" ;;
  x86_64|amd64)  ARCH="x64" ;;
  *) echo "不支持的架构: $MACHINE" >&2; exit 1 ;;
esac

if [ -n "${LITMUS_JAR:-}" ]; then
  JAR_PATH="$LITMUS_JAR"
else
  echo "==> 1/3 构建可执行 jar"
  GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  if [ "$GIT_COMMIT" != "unknown" ] && [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    GIT_COMMIT="${GIT_COMMIT}-dirty"
  fi
  ./mvnw -q clean package -DskipTests -Dlitmus.git.commit="${GIT_COMMIT}"
  JAR_PATH="target/${MAIN_JAR}"
fi
if [ ! -f "$JAR_PATH" ]; then
  echo "未找到 ${JAR_PATH}" >&2
  exit 1
fi

echo "==> 2/3 jpackage 打包 app-image（${PLAT}-${ARCH}）"
STAGE="$(mktemp -d)"
cp "$JAR_PATH" "$STAGE/"
OUT_DIR="$(mktemp -d)"

jpackage \
  --name "$APP_NAME" \
  --input "$STAGE" \
  --main-jar "$MAIN_JAR" \
  --main-class io.github.agentlitmus.Cli \
  --type app-image \
  --app-version "$VERSION" \
  --vendor "AgentLitmus" \
  --description "AgentLitmus 智能体长期记忆评测基准" \
  --dest "$OUT_DIR"

echo "==> 3/3 压缩产物"
mkdir -p dist
TARBALL="dist/${APP_NAME}-${PLAT}-${ARCH}.tar.gz"
# macOS 的 app-image 是 .app bundle（agent-litmus.app/Contents/MacOS/…），
# Linux 是普通目录（agent-litmus/bin/…）；tar 打包顶层条目，pip 壳按平台找可执行
if [ "$PLAT" = "macos" ]; then
  IMAGE_DIR="${APP_NAME}.app"
  BIN_PATH="${IMAGE_DIR}/Contents/MacOS/${APP_NAME}"
else
  IMAGE_DIR="${APP_NAME}"
  BIN_PATH="${IMAGE_DIR}/bin/${APP_NAME}"
fi
if [ ! -d "$OUT_DIR/$IMAGE_DIR" ]; then
  echo "jpackage 产物布局不符合预期：未找到 $OUT_DIR/$IMAGE_DIR" >&2
  ls -la "$OUT_DIR" >&2
  exit 1
fi
tar -czf "$TARBALL" -C "$OUT_DIR" "$IMAGE_DIR"
rm -rf "$STAGE" "$OUT_DIR"

# 自检：产物里的可执行必须真的能跑，打出来才发现缺模块就晚了
PROBE="$(mktemp -d)"
tar -xzf "$TARBALL" -C "$PROBE"
if ! "$PROBE/$BIN_PATH" --help >/dev/null 2>&1; then
  echo "自检失败：打包出的可执行跑不起来（--help 退出码非零）" >&2
  rm -rf "$PROBE"
  exit 1
fi
rm -rf "$PROBE"

SHA256="$(shasum -a 256 "$TARBALL" | awk '{print $1}')"
echo "产物: ${TARBALL}"
echo "sha256: ${SHA256}"
