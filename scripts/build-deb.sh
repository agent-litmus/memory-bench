#!/usr/bin/env bash
#
# 打包 openKylin / Debian 系安装包（.deb）
#
# 依赖：JDK 17+（含 jpackage）、Maven（或 ./mvnw）、fakeroot（重新打包用）
# 产物：dist/agent-litmus_<version>_<arch>.deb
#
# 用法：
#   ./scripts/build-deb.sh
#
# 安装后可直接执行：agent-litmus run
#
set -euo pipefail

cd "$(dirname "$0")/.."

APP_NAME="agent-litmus"
ARCH="$(dpkg --print-architecture 2>/dev/null || echo amd64)"

# 版本只认 pom.xml，不在脚本里再写一遍——两边各写一份迟早对不上，
# 对不上的表现是「deb 打出来了但里面是旧版本」，比直接失败更难查
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)"
if [ -z "$VERSION" ]; then
  echo "无法从 pom.xml 读出版本号" >&2
  exit 1
fi
MAIN_JAR="memory-bench-${VERSION}.jar"

# 重新打包需要一个能伪造 root 归属的工具，否则 /opt 下文件会变成非 root
if ! command -v fakeroot >/dev/null 2>&1; then
  echo "需要 fakeroot，请先安装：sudo apt install fakeroot" >&2
  exit 1
fi

echo "==> 1/4 构建可执行 jar"
# 把 commit 钉进产物：运行契约要能回答「这批数字出自哪个版本」，
# 否则发布出去的 jar 里 litmusVersion/gitCommit 永远是 unknown
GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
# 工作区脏就如实标脏：否则 deb 里的 gitCommit 指向一个不含这些改动的提交，
# 「这批数字出自哪个版本」这个问题就答错了，而且错得看不出来
if [ "$GIT_COMMIT" != "unknown" ] && [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  GIT_COMMIT="${GIT_COMMIT}-dirty"
fi
./mvnw -q clean package -DskipTests -Dlitmus.git.commit="${GIT_COMMIT}"

JAR_PATH="target/${MAIN_JAR}"
if [ ! -f "$JAR_PATH" ]; then
  echo "未找到 ${JAR_PATH}，请检查 pom.xml 中的版本号是否与脚本一致" >&2
  ls -1 target/*.jar 2>/dev/null || true
  exit 1
fi

echo "==> 2/4 使用 jpackage 打包 deb（应用镜像 + 默认控制脚本）"
rm -rf dist
mkdir -p dist

# jpackage 会把 --input 目录整个塞进 /opt/<app>/lib/。直接指 target 就等于把
# classes/ test-classes/ surefire-reports/ 一起发出去，所以只暂存可执行 jar
STAGE="$(mktemp -d)"
cp "$JAR_PATH" "$STAGE/"

jpackage \
  --name "$APP_NAME" \
  --input "$STAGE" \
  --main-jar "$MAIN_JAR" \
  --main-class io.github.agentlitmus.Cli \
  --type deb \
  --app-version "$VERSION" \
  --vendor "AgentLitmus" \
  --description "AgentLitmus 智能体长期记忆评测基准" \
  --dest dist \
  --linux-shortcut \
  --linux-menu-group "Development"

rm -rf "$STAGE"

echo "==> 3/4 注入 /usr/bin 软链（使 agent-litmus 命令直接可用）并重新打包"
DEB="dist/${APP_NAME}_${VERSION}_${ARCH}.deb"
WORK="$(mktemp -d)"
dpkg-deb -R "$DEB" "$WORK"

# postinst：保留桌面菜单逻辑，并在 configure 阶段建立 PATH 软链
cat > "$WORK/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
package_type=deb
case "$1" in
    configure)
        xdg-desktop-menu install /opt/agent-litmus/lib/agent-litmus-agent-litmus.desktop || true
        ln -sf /opt/agent-litmus/bin/agent-litmus /usr/bin/agent-litmus
    ;;
    abort-upgrade|abort-remove|abort-deconfigure)
    ;;
    *)
        echo "postinst called with unknown argument \`$1'" >&2
        exit 1
    ;;
esac
exit 0
EOF

# prerm：卸载时清理软链
cat > "$WORK/DEBIAN/prerm" <<'EOF'
#!/bin/sh
set -e
rm -f /usr/bin/agent-litmus
exit 0
EOF

chmod 0755 "$WORK/DEBIAN/postinst" "$WORK/DEBIAN/prerm"
rm -f "$DEB"
fakeroot dpkg-deb -b "$WORK" "$DEB" >/dev/null
rm -rf "$WORK"

echo "==> 4/4 完成"
ls -1 dist/*.deb
echo
echo "版本: ${VERSION}  提交: ${GIT_COMMIT}  架构: ${ARCH}"
echo "安装: sudo dpkg -i dist/${APP_NAME}_${VERSION}*.deb"
echo "运行: agent-litmus run --out ~/litmus-result"
