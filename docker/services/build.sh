#!/usr/bin/env bash
# docker/services/build.sh
# Build 4 module images. Each image is tagged with both :dev (mutable alias)
# and :sha-<7char> (reproducible).
# Usage: ./docker/services/build.sh [module-name ...]
#   No args: build all 4 modules.
#   With args: build only the named modules (faster during dev).
set -euo pipefail

cd "$(dirname "$0")/../.."  # repo root

ALL_MODULES=(external-api calculator synchronizer cleanup)

if [ $# -eq 0 ]; then
  modules=("${ALL_MODULES[@]}")
else
  modules=("$@")
fi

# Short SHA for reproducible tagging.
SHA="$(git rev-parse --short=7 HEAD 2>/dev/null || echo nosha)"

for mod in "${modules[@]}"; do
  case "$mod" in
    external-api|calculator|synchronizer|cleanup) ;;
    *) echo "Unknown module: $mod" >&2; exit 2 ;;
  esac
  jar="module-${mod}/build/libs/module-${mod}-0.0.1-SNAPSHOT.jar"
  if [ ! -f "$jar" ]; then
    echo "Missing jar: $jar — run ./gradlew :module-${mod}:bootJar first" >&2
    exit 3
  fi
  echo "==> Building maple/${mod}:dev + :sha-${SHA}"
  docker build \
    --build-arg "MODULE_NAME=${mod}" \
    --build-arg "JAR_PATH=${jar}" \
    -t "maple/${mod}:dev" \
    -t "maple/${mod}:sha-${SHA}" \
    -f docker/Dockerfile.runtime \
    .
done

echo "==> Built: ${modules[*]} (sha=${SHA})"