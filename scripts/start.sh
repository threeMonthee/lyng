#!/usr/bin/env bash
# 在本机启动此间有灵：准备数据库 → 构建网页 → 构建并启动服务 → 打开 http://127.0.0.1:8080
#
#   scripts/start.sh          用真实模型（需要仓库根的 .env 里有 OPENROUTER_API_KEY 与 DASHSCOPE_API_KEY）
#   scripts/start.sh --fake   用确定性替身，不调用任何服务、不产生费用，用来看界面和流程
#   scripts/start.sh --skip-build   跳过构建，直接启动上次构建好的版本
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

FAKE=0
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --fake) FAKE=1 ;;
    --skip-build) BUILD=0 ;;
    *) echo "不认识的参数：$arg" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1m%s\033[0m\n' "$*"; }

# ---- JDK 25 ----
if [[ "$(uname)" == "Darwin" ]] && /usr/libexec/java_home -v 25 >/dev/null 2>&1; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 25)
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! java -version 2>&1 | grep -q 'version "25'; then
  echo "需要 JDK 25（当前：$(java -version 2>&1 | head -1)）。装好后设置 JAVA_HOME 再试。" >&2
  exit 1
fi

# ---- 数据库 ----
db_ready() { (exec 3<>/dev/tcp/127.0.0.1/5432) 2>/dev/null; }
if db_ready; then
  say "数据库：使用本机 5432 端口上的 PostgreSQL（需要 lyng 库、lyng 用户和 vector 扩展，见 README）"
elif command -v docker >/dev/null 2>&1; then
  say "数据库：本机没有运行 PostgreSQL，用 Docker 启动 pgvector"
  docker compose up -d --wait db
else
  cat >&2 <<'MSG'
没有找到 PostgreSQL。任选一种：
  1) 安装 Docker Desktop，再运行本脚本（会自动启动数据库容器）
  2) brew install postgresql@17 pgvector && brew services start postgresql@17，然后：
       createuser -s lyng && psql -d postgres -c "ALTER USER lyng PASSWORD 'lyng'"
       createdb -O lyng lyng && createdb -O lyng lyng_test
MSG
  exit 1
fi

# ---- 构建 ----
if [[ $BUILD == 1 ]]; then
  say "构建网页"
  (cd client && { [[ -d node_modules ]] || npm ci; } && npm run build)
  say "构建服务"
  if command -v mvn >/dev/null 2>&1; then MVN=mvn; else MVN=./mvnw; fi
  (cd server && $MVN -q -B package -DskipTests)
fi

# ---- 启动 ----
if [[ $FAKE == 1 ]]; then
  export LYNG_MODEL_BACKEND=fake LYNG_EMBEDDING_BACKEND=fake
  say "替身模式：不调用模型，不产生费用"
elif [[ ! -f .env ]] || ! grep -q '^OPENROUTER_API_KEY=.\+' .env; then
  echo "仓库根的 .env 里没有 OPENROUTER_API_KEY。复制 .env.example 为 .env 并填写，或用 --fake 先看界面。" >&2
  exit 1
fi
say "启动：http://127.0.0.1:${LYNG_PORT:-8080}（Ctrl+C 停止）"
cd server
exec java -jar target/lyng.jar
