#!/bin/bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/backup/maple}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP_DIR"

echo "=== Maple Backup ${TIMESTAMP} ==="

# PostgreSQL
echo "[postgres] pg_dump..."
docker exec maple-postgres pg_dump -U "${DB_USERNAME:-maple}" "${DB_SCHEMA_NAME:-maple_expectation}" \
  | gzip > "${BACKUP_DIR}/db_${TIMESTAMP}.sql.gz"
echo "[postgres] done: db_${TIMESTAMP}.sql.gz ($(du -h "${BACKUP_DIR}/db_${TIMESTAMP}.sql.gz" | cut -f1))"

# Redis (optional — cache, Write-Back handles durability)
echo "[redis] BGSAVE..."
docker exec maple-redis redis-cli BGSAVE >/dev/null 2>&1 || true
docker cp maple-redis:/data/dump.rdb "${BACKUP_DIR}/redis_${TIMESTAMP}.rdb" 2>/dev/null || echo "[redis] skipped (no data)"

# Kafka topics list (data managed by retention)
echo "[kafka] topic list..."
docker exec maple-kafka kafka-topics --bootstrap-server localhost:9092 --list \
  > "${BACKUP_DIR}/kafka_topics_${TIMESTAMP}.txt" 2>/dev/null || echo "[kafka] skipped"

# Cleanup: keep last 30 days
find "$BACKUP_DIR" -name "*.gz" -mtime +30 -delete 2>/dev/null || true
find "$BACKUP_DIR" -name "*.rdb" -mtime +30 -delete 2>/dev/null || true
find "$BACKUP_DIR" -name "*.txt" -mtime +30 -delete 2>/dev/null || true

echo "=== Backup complete ==="
ls -lh "${BACKUP_DIR}/" | tail -5
