#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "============================================"
echo "  Kudu 控制台 - 构建"
echo "============================================"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
command -v "$JAVA_BIN" >/dev/null 2>&1 || { echo "[错误] 未找到 JDK 17，请安装或设置 JAVA_HOME"; exit 1; }

MVN=""
if command -v mvn >/dev/null 2>&1; then
  MVN="mvn"
else
  MVN="$(ls -d "$HOME"/.m2/wrapper/dists/apache-maven-*/*/bin/mvn 2>/dev/null | sort -r | head -1 || true)"
fi
[ -n "$MVN" ] || { echo "[错误] 未找到 Maven"; exit 1; }

echo "[1/3] 构建前端..."
pushd web >/dev/null
if [ ! -d node_modules ]; then
  npm install --no-audit --no-fund
fi
npm run build
popd >/dev/null

[ -f src/main/resources/static/index.html ] || { echo "[错误] 前端没有产出 index.html"; exit 1; }

echo "[2/3] 打包后端..."
"$MVN" -B -DskipTests clean package

echo "[3/3] 完成: target/kudu-console.jar"
