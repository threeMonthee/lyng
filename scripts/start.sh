#!/usr/bin/env bash
# 一键启动：构建客户端 → 构建服务端 → 运行。数据在 data/lyng.db（SQLite，已 gitignore；替身模式用 data/fake.db）。
# 用法：scripts/start.sh [--fake]    --fake 使用确定性模型替身，不发网络请求、不花钱。
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--fake" ]]; then
  export LYNG_MODEL_BACKEND=fake
  export LYNG_FAKE_DELAY_MS="${LYNG_FAKE_DELAY_MS:-40}"
  export LYNG_DB="${LYNG_DB:-../data/fake.db}"   # 替身的数据不和真实的混在一起
fi

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25)" || { echo "需要 JDK 25（设置 JAVA_HOME 指向它）" >&2; exit 1; }
  export JAVA_HOME
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if [[ "${LYNG_MODEL_BACKEND:-openrouter}" != "fake" ]] && ! grep -qs '^OPENROUTER_API_KEY=.' .env && [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
  echo "真实模型需要 OPENROUTER_API_KEY：复制 .env.example 为 .env 并填写；只想看界面用 --fake" >&2
  exit 1
fi

( cd client && { [[ -d node_modules ]] || npm ci; } && npm run build )
( cd server && mvn -q -B -DskipTests package )

cd server
echo "打开 http://localhost:${LYNG_PORT:-8080}"
exec "$JAVA" -jar target/lyng.jar
