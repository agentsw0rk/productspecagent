#!/usr/bin/env bash
set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_PID=""
FRONTEND_PID=""
MINIO_STARTED_BY_US=0

cleanup() {
  echo ""
  echo "Stopping services..."
  [ -n "$FRONTEND_PID" ] && kill "$FRONTEND_PID" 2>/dev/null
  [ -n "$BACKEND_PID" ] && kill "$BACKEND_PID" 2>/dev/null
  if [ "$MINIO_STARTED_BY_US" = "1" ]; then
    docker rm -f productspec-minio-dev >/dev/null 2>&1 || true
  fi
  wait 2>/dev/null
  echo "Done."
}
trap cleanup EXIT INT TERM

echo "=== Checking MinIO ==="
if curl -sf http://localhost:9000/minio/health/live >/dev/null 2>&1; then
  echo "MinIO already running on localhost:9000 — using it."
else
  echo "Starting MinIO container (productspec-minio-dev)..."
  docker run -d --rm \
    --name productspec-minio-dev \
    -p 9000:9000 -p 9001:9001 \
    -e MINIO_ROOT_USER=minioadmin \
    -e MINIO_ROOT_PASSWORD=minioadmin \
    minio/minio:RELEASE.2025-09-07T16-13-09Z \
    server /data --console-address ":9001" >/dev/null
  MINIO_STARTED_BY_US=1

  echo "Waiting for MinIO to become ready..."
  for i in $(seq 1 30); do
    if curl -sf http://localhost:9000/minio/health/live >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done

  echo "Creating bucket productspec-data..."
  docker run --rm --network host minio/mc:latest sh -c "
    mc alias set local http://localhost:9000 minioadmin minioadmin &&
    mc mb --ignore-existing local/productspec-data
  " >/dev/null
fi

export S3_ENDPOINT="http://localhost:9000"
export S3_BUCKET="productspec-data"
export S3_ACCESS_KEY="minioadmin"
export S3_SECRET_KEY="minioadmin"
export S3_PATH_STYLE="true"
export S3_REGION="us-east-1"

# Claude / proxy example values:
# export https_proxy="http://meta-proxy.netrtl.com:8080"
# export http_proxy="http://meta-proxy.netrtl.com:8080"
# export NO_PROXY="*.netrtl.com,10.96.0.0/14"
# export no_proxy="*.netrtl.com,10.96.0.0/14"
#
# export ANTHROPIC_BASE_URL="https://llm.ai.netrtl.com"
# export ANTHROPIC_AUTH_TOKEN="${ANTHROPIC_AUTH_TOKEN:-set-me}"
# # Quelle: https://llm-keys.ai.netrtl.com/
# export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
# export CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1
# export ANTHROPIC_DEFAULT_HAIKU_MODEL="claude-haiku-4.5"
# export ANTHROPIC_DEFAULT_SONNET_MODEL="claude-sonnet-4.6"
# export ANTHROPIC_DEFAULT_OPUS_MODEL="claude-opus-4.6"

export LLM_PROVIDER="${LLM_PROVIDER:-openai}"
case "$LLM_PROVIDER" in
  openai)
    export AGENT_MODEL_RESOLVER="${AGENT_MODEL_RESOLVER:-openai}"
    export KOOG_OPENAI_ENABLED="${KOOG_OPENAI_ENABLED:-true}"
    export KOOG_ANTHROPIC_ENABLED="${KOOG_ANTHROPIC_ENABLED:-false}"
    export AGENT_MODEL_SMALL="${AGENT_MODEL_SMALL:-gpt-5.4-nano}"
    export AGENT_MODEL_MEDIUM="${AGENT_MODEL_MEDIUM:-gpt-5.4-mini}"
    export AGENT_MODEL_LARGE="${AGENT_MODEL_LARGE:-gpt-5-2}"
    ;;
  claude)
    export AGENT_MODEL_RESOLVER="${AGENT_MODEL_RESOLVER:-claude}"
    export KOOG_OPENAI_ENABLED="${KOOG_OPENAI_ENABLED:-false}"
    export KOOG_ANTHROPIC_ENABLED="${KOOG_ANTHROPIC_ENABLED:-true}"
    export ANTHROPIC_BASE_URL="${ANTHROPIC_BASE_URL:-https://llm.ai.netrtl.com}"
    export AGENT_MODEL_SMALL="${AGENT_MODEL_SMALL:-claude-haiku-4-5}"
    export AGENT_MODEL_MEDIUM="${AGENT_MODEL_MEDIUM:-claude-sonnet-4-6}"
    export AGENT_MODEL_LARGE="${AGENT_MODEL_LARGE:-claude-sonnet-4-6}"
    export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
    export CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1
    ;;
  *)
    echo "Unsupported LLM_PROVIDER: $LLM_PROVIDER (expected: openai or claude)" >&2
    exit 1
    ;;
esac

export AUTH_JWT_SECRET="${AUTH_JWT_SECRET:-dev-secret-change-me-dev-secret-change-me-dev-secret}"
export AUTH_ADMIN_EMAILS="${AUTH_ADMIN_EMAILS:-*}"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"

echo "=== LLM Provider: $LLM_PROVIDER ==="
echo "=== Starting Backend (Spring Boot) ==="
cd "$ROOT_DIR/backend"
./gradlew bootRun --quiet &
BACKEND_PID=$!

echo "=== Starting Frontend (Next.js) ==="
cd "$ROOT_DIR/frontend"
npm run dev &
FRONTEND_PID=$!

echo ""
echo "Backend:  http://localhost:8080"
echo "Frontend: http://localhost:3000"
echo "MinIO:    http://localhost:9001 (console)"
echo ""
echo "Press Ctrl+C to stop services."

wait
