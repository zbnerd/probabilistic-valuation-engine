#!/bin/sh
# docker/services/entrypoint-wrapper.sh
# Reads MINIO_SECRET_KEY_FILE (default: /run/secrets/sa-${MODULE_NAME}) and
# exports MINIO_SECRET_KEY for the JVM. Logs each step to container stdout
# (visible via `docker logs`) for debugging.
set -eu

MODULE_NAME="${MODULE_NAME:-unknown}"
SECRET_FILE="${MINIO_SECRET_KEY_FILE:-/run/secrets/sa-${MODULE_NAME}}"

if [ -f "${SECRET_FILE}" ]; then
  export MINIO_SECRET_KEY="$(cat "${SECRET_FILE}")"
  echo "[entrypoint] loaded MINIO_SECRET_KEY from ${SECRET_FILE} (module=${MODULE_NAME})"
else
  echo "[entrypoint] WARNING: ${SECRET_FILE} not found; MINIO_SECRET_KEY from env only" >&2
fi

# Spring property resolution: ${MINIO_SECRET_KEY:} returns empty string if unset.
# We want hard failure if the secret never got bound (production would silently
# use empty creds and get S3 Access Denied from MinIO, masking the real bug).
if [ -z "${MINIO_SECRET_KEY:-}" ]; then
  echo "[entrypoint] FATAL: MINIO_SECRET_KEY is unset; refusing to start" >&2
  exit 11
fi

exec java ${JAVA_OPTS} -jar /app.jar