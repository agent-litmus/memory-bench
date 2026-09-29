#!/usr/bin/env bash
#
# 打包 openKylin / Debian 系安装包（.deb）
#
# 依赖：JDK 17+（含 jpackage）、Maven（或 ./mvnw）
# 产物：dist/agent-litmus_<version>_<arch>.deb
#
# 用法：
#   ./scripts/build-deb.sh
#
# 安装后可直接执行：agent-litmus run
#
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="0.1.0"
APP_NAME="agent-litmus"
MAIN_JAR="memory-bench-${VERSION}-SNAPSHOT.jar"

echo "==> 1/3 构建可执行 jar"
./mvnw -q clean package -DskipTests

JAR_PATH="target/${MAIN_JAR}"
if [ ! -f "$JAR_PATH" ]; then
  echo "未找到 ${JAR_PATH}，请检查 pom.xml 中的版本号是否与脚本一致" >&2
  ls -1 target/*.jar 2>/dev/null || true
  exit 1
fi

echo "==> 2/3 使用 jpackage 打包 deb"
rm -rf dist
mkdir -p dist

jpackage \
  --name "$APP_NAME" \
  --input target \
  --main-jar "$MAIN_JAR" \
  --main-class io.github.zaojiaoci.agentlitmus.Cli \
  --type deb \
  --app-version "$VERSION" \
  --vendor "AgentLitmus" \
  --description "AgentLitmus 智能体长期记忆评测基准" \
  --dest dist \
  --linux-shortcut \
  --linux-menu-group "Development"

echo "==> 3/3 完成"
ls -1 dist/*.deb
echo
echo "安装: sudo dpkg -i dist/${APP_NAME}_${VERSION}*.deb"
echo "运行: agent-litmus run --out ~/litmus-result"
