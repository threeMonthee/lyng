#!/usr/bin/env bash
# 一键启动：数据库（Docker）→ 构建客户端 → 构建服务端 → 运行。
# 用法：scripts/start.sh [--fake]    --fake 使用确定性模型替身，不发网络请求、不花钱。
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--fake" ]]; then
  export LYNG_MODEL_BACKEND=fake
fi

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25)" || { echo "需要 JDK 25（设置 JAVA_HOME 指向它）" >&2; exit 1; }
  export JAVA_HOME
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [[ ! -f .env ]]; then
  echo "缺少 .env：复制 .env.example 为 .env，并填写 LYNG_PASSWORD（真实模型还要 OPENROUTER_API_KEY）" >&2
  exit 1
fi

# 数据库：LYNG_DB_URL 指向别处时跳过 Docker
if [[ -z "${LYNG_DB_URL:-}" ]]; then
  docker compose up -d --wait postgres
fi

( cd client && { [[ -d node_modules ]] || npm ci; } && npm run build )
( cd server && mvn -q -B -DskipTests package )

cd server
exec "$JAVA" -jar target/lyng.jar
