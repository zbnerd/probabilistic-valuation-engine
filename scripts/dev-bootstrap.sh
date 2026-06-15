#!/usr/bin/env bash
# scripts/dev-bootstrap.sh
# One-line dev env generator. Run once after `git clone` or whenever
# a fresh env set is needed.
#
# Generates:
#   .env.bootstrap          — root + 4 SA secret keys
#   .env.ext-api            — SA creds for external-api
#   .env.calculator         — SA creds for calculator
#   .env.synchronizer       — SA creds for synchronizer
#   .env.cleanup            — SA creds for cleanup
#
# Idempotent: re-running regenerates the full set.
# Existing files are overwritten.

set -euo pipefail

cd "$(dirname "$0")/.."

# Root creds: read from current .env (assumes MINIO_ROOT_USER/PASSWORD already set there).
# If absent, default to the dev minioadmin pair.
: "${MINIO_ROOT_USER:=minioadmin}"
: "${MINIO_ROOT_PASSWORD:=minioadmin}"

cat > .env.bootstrap <<EOF
MINIO_ENDPOINT=http://localhost:9000
MINIO_ROOT_USER=${MINIO_ROOT_USER}
MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}

SA_EXT_API_SECRET_KEY=$(openssl rand -hex 32)
SA_CALCULATOR_SECRET_KEY=$(openssl rand -hex 32)
SA_SYNCHRONIZER_SECRET_KEY=$(openssl rand -hex 32)
SA_CLEANUP_SECRET_KEY=$(openssl rand -hex 32)
EOF
chmod 600 .env.bootstrap

declare -A sa_keys
sa_keys[ext-api]=SA_EXT_API_SECRET_KEY
sa_keys[calculator]=SA_CALCULATOR_SECRET_KEY
sa_keys[synchronizer]=SA_SYNCHRONIZER_SECRET_KEY
sa_keys[cleanup]=SA_CLEANUP_SECRET_KEY

for sa in ext-api calculator synchronizer cleanup; do
  secret=$(grep "^${sa_keys[$sa]}=" .env.bootstrap | cut -d= -f2-)
  cat > ".env.${sa}" <<EOF2
MINIO_ACCESS_KEY=${sa}
MINIO_SECRET_KEY=${secret}
EOF2
  chmod 600 ".env.${sa}"
done

echo "[dev-bootstrap] generated .env.bootstrap + 4 × .env.<module>"
echo "[dev-bootstrap] next: docker compose up -d minio && docker compose up minio-bootstrap"
