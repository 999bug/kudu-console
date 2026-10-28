#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-8088}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
command -v "$JAVA_BIN" >/dev/null 2>&1 || { echo "[错误] 未找到 JDK 17"; exit 1; }

if [ ! -f target/kudu-console.jar ]; then
  echo "未找到 jar，先执行构建..."
  ./build.sh
fi

echo "============================================"
echo "  Kudu 控制台  http://localhost:$PORT/"
echo "  停止: Ctrl+C"
echo "============================================"

(
  sleep 4
  if command -v xdg-open >/dev/null 2>&1; then xdg-open "http://localhost:$PORT/" >/dev/null 2>&1 || true
  elif command -v open >/dev/null 2>&1; then open "http://localhost:$PORT/" >/dev/null 2>&1 || true
  fi
) &

exec "$JAVA_BIN" -jar target/kudu-console.jar --server.port="$PORT"
