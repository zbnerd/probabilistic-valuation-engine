#!/bin/bash
# Airflow connection setup for maple pipeline.
# Run after: docker compose -f docker-compose.airflow.yml up -d
#
# Note: Airflow containers run with network_mode: host, so the host is
# reachable as localhost (the container shares the host's network stack).
# This avoids bridge hairpin NAT, which is blocked for non-coolify ports
# (modules on 8081-8084 cannot be reached from maple-network containers
# via host.docker.internal or the bridge gateway 172.20.0.1).

AIRFLOW_HOST="http://localhost:8180"

echo "Creating Airflow connections..."

# External API (localhost → host port 8081)
docker exec maple-airflow-scheduler airflow connections add external_api \
  --conn-type http \
  --conn-host localhost \
  --conn-port 8081 \
  --conn-schema http

# Calculator (localhost → host port 8082)
docker exec maple-airflow-scheduler airflow connections add calculator \
  --conn-type http \
  --conn-host localhost \
  --conn-port 8082 \
  --conn-schema http

echo "Done. Verify with: docker exec maple-airflow-scheduler airflow connections list"
