#!/usr/bin/env bash
# scripts/run-pipeline-tests.sh
#
# Run Airflow DAG pytests inside the maple-airflow-scheduler container with
# docker-first enforcement. Fail-fast if scheduler is not running so tests
# never silently execute against a stale container.
#
# Why this exists: pytest under docker/airflow/dags/tests/ depends on the
# `airflow` package + plugins (kafka, http) installed in the scheduler image.
# Running pytest on the host fails with ImportError; running pytest inside
# `maple-airflow-scheduler` succeeds. Earlier workflow invoked the long
# `docker exec ... pytest ...` command directly from memory, with no
# container-alive check — a stopped scheduler surfaced as a confusing
# `Error response from daemon: container not started` rather than the
# actionable `docker compose up -d airflow-scheduler`.
#
# Usage:
#   scripts/run-pipeline-tests.sh                       # all tests
#   scripts/run-pipeline-tests.sh tests/test_phase_pipeline_factory.py
#   scripts/run-pipeline-tests.sh tests/ -k TestMakePhaseDag -v
#
# Exit codes:
#   0  all tests passed
#   1  pytest failures
#   2  scheduler container missing / not running
#   3  airflow dags list sanity check failed (scheduler process stuck)
#
# Pre-req: docker compose for Airflow services has been started at least once
# (so the `maple-airflow-scheduler` container exists). Use:
#   docker compose -f docker-compose.yml -f docker-compose.airflow.yml \
#     up -d airflow-webserver airflow-scheduler
# Or run the `pipeline-test` skill — its step 1b does this automatically.

set -uo pipefail

CONTAINER="maple-airflow-scheduler"
DAGS_DIR="/opt/airflow/dags"
TESTS_DIR="${DAGS_DIR}/tests"

# 1) Container exists + running. Filter on both name and status so a stopped
# container (status=exited) does not silently pass the check.
if ! docker ps --filter "name=^${CONTAINER}$" --filter "status=running" --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "ERROR: ${CONTAINER} is not running." >&2
  echo "Start it via:" >&2
  echo "  docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d airflow-webserver airflow-scheduler" >&2
  echo "Or invoke the pipeline-test skill (step 1b brings all docker services up)." >&2
  exit 2
fi

# 2) Scheduler process responsive. Proves the scheduler is not stuck in a
# crashloop and that `airflow` CLI is on PATH inside the container.
if ! docker exec "${CONTAINER}" airflow dags list > /dev/null 2>&1; then
  echo "ERROR: ${CONTAINER} responds but `airflow dags list` failed." >&2
  echo "Scheduler likely in crashloop. Check logs:" >&2
  echo "  docker logs ${CONTAINER} --tail 50" >&2
  exit 3
fi
echo "${CONTAINER}: docker-first pre-check passed"

# 3) Run pytest. Forward all CLI args so users can pass -k, --co, file paths, etc.
# Default to `tests/` when no positional arg given.
if [ "$#" -eq 0 ]; then
  set -- "${TESTS_DIR}"
fi

exec docker exec "${CONTAINER}" \
  bash -c "cd ${DAGS_DIR} && python3 -m pytest $*"
