#!/usr/bin/env bash
#
# 在线升级：比对 GitHub Release 最新版本 → 下载 jar → 校验 sha256 → 原子替换 → 重启
#
# 设计原则和前端项目的 Service Worker 静默更新一致：
#   - 版本没变就什么都不做
#   - 校验不通过绝不覆盖线上
#   - 新版本起不来就自动回滚到旧 jar
#
# 用法：
#   ./upgrade.sh              # 升级到最新 Release
#   ./upgrade.sh v1.0.2       # 升级/回滚到指定版本
#   CHECK_ONLY=1 ./upgrade.sh # 只检查，不安装（适合挂 cron 做巡检）
#
# 环境变量：
#   REPO       默认 999bug/kudu-crud
#   APP_DIR    默认 /opt/kudu-console
#   SERVICE    systemd 服务名，默认 kudu-console；设为空则跳过重启
#   MODE       systemd | docker，默认自动判断
#   JAR        显式指定 jar 落盘路径，默认按 MODE 推导
set -euo pipefail

REPO="${REPO:-999bug/kudu-crud}"
APP_DIR="${APP_DIR:-/opt/kudu-console}"
SERVICE="${SERVICE:-kudu-console}"
VERSION_FILE="$APP_DIR/VERSION"
CHECK_ONLY="${CHECK_ONLY:-0}"

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { log "错误: $*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "需要 curl"

# ---------- 0. 判断运行方式，决定 jar 放哪 ----------
MODE="${MODE:-}"
if [ -z "$MODE" ]; then
  if [ -f "$APP_DIR/docker-compose.yml" ] || [ -f "$APP_DIR/compose.yaml" ]; then
    MODE="docker"
  else
    MODE="systemd"
  fi
fi

if [ -n "${JAR:-}" ]; then
  : # 调用方指定
elif [ "$MODE" = "docker" ]; then
  # docker-compose 把宿主机的 ./release 挂到容器 /app/release
  JAR="$APP_DIR/release/kudu-console.jar"
else
  JAR="$APP_DIR/kudu-console.jar"
fi

# ---------- 1. 解析目标版本 ----------
CURRENT="$(cat "$VERSION_FILE" 2>/dev/null || echo 'v0.0.0')"

if [ $# -ge 1 ]; then
  TARGET="$1"
else
  TARGET="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
    | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | head -1 | sed 's/.*"\([^"]*\)"$/\1/')" || true
  [ -n "${TARGET:-}" ] || die "拿不到最新版本号，检查网络或仓库是否已有 Release"
fi

log "当前版本: $CURRENT    目标版本: $TARGET    MODE=$MODE    JAR=$JAR"

if [ "$CURRENT" = "$TARGET" ]; then
  log "已是最新，无需升级"
  exit 0
fi

if [ "$CHECK_ONLY" = "1" ]; then
  log "有新版本可用（CHECK_ONLY，不安装）"
  exit 10
fi

# ---------- 2. 下载并校验 ----------
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

BASE="https://github.com/$REPO/releases/download/$TARGET"
log "下载 $BASE/kudu-console.jar"
curl -fsSL --retry 3 -o "$TMP/kudu-console.jar" "$BASE/kudu-console.jar" \
  || die "下载失败，版本 $TARGET 可能不存在"

if curl -fsSL --retry 3 -o "$TMP/kudu-console.jar.sha256" "$BASE/kudu-console.jar.sha256" 2>/dev/null; then
  if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$TMP" && sha256sum -c kudu-console.jar.sha256 ) || die "sha256 校验失败，放弃升级"
  elif command -v shasum >/dev/null 2>&1; then
    EXPECT="$(awk '{print $1}' "$TMP/kudu-console.jar.sha256")"
    ACTUAL="$(shasum -a 256 "$TMP/kudu-console.jar" | awk '{print $1}')"
    [ "$EXPECT" = "$ACTUAL" ] || die "sha256 校验失败，放弃升级"
  else
    log "警告: 没有 sha256 工具，跳过校验"
  fi
  log "sha256 校验通过"
else
  log "警告: 该版本未提供 .sha256，跳过校验"
fi

# ---------- 3. 备份 + 原子替换 ----------
mkdir -p "$(dirname "$JAR")"
if [ -f "$JAR" ]; then
  cp -p "$JAR" "$JAR.bak.$(date +%s)"
fi

# 同目录 mv 是原子操作，不会出现「半个 jar」
install -m 0644 "$TMP/kudu-console.jar" "$JAR.new"
mv -f "$JAR.new" "$JAR"
log "jar 已替换"

# ---------- 4. 重启并健康检查 ----------
restart_app() {
  case "$MODE" in
    docker)  ( cd "$APP_DIR" && docker compose up -d --force-recreate kudu-console ) ;;
    systemd) [ -n "$SERVICE" ] && systemctl restart "$SERVICE" || true ;;
    none)    return 0 ;;
    *)       die "未知 MODE: $MODE" ;;
  esac
}

# 只探首页存活，不探 /api：业务接口依赖 Kudu 集群，
# 集群临时不可达时不应该被误判成「新版本坏了」而触发回滚
healthy() {
  local code
  for _ in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${PORT:-8088}/" || true)"
    [ "$code" = "200" ] && return 0
    sleep 2
  done
  return 1
}

log "重启服务（MODE=$MODE）"
restart_app

if healthy; then
  echo "$TARGET" > "$VERSION_FILE"
  log "升级完成: $CURRENT -> $TARGET"
  # 保留最近 3 个备份
  ls -1t "$JAR".bak.* 2>/dev/null | tail -n +4 | xargs rm -f 2>/dev/null || true
else
  log "新版本健康检查失败，回滚到 $CURRENT"
  LATEST_BAK="$(ls -1t "$JAR".bak.* 2>/dev/null | head -1 || true)"
  if [ -n "$LATEST_BAK" ]; then
    mv -f "$LATEST_BAK" "$JAR"
    restart_app
  fi
  exit 1
fi
