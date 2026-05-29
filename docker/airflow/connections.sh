#!/bin/bash
# Airflow connection setup for maple pipeline.
# Run after: docker compose -f docker-compose.airflow.yml up -d

AIRFLOW_HOST="http://localhost:8180"

echo "Creating Airflow connections..."

# External API (host.docker.internal → host port 8081)
docker exec maple-airflow-scheduler airflow connections add external_api \
  --conn-type http \
  --conn-host http://host.docker.internal \
  --conn-port 8081 \
  --conn-schema http

echo "Done. Verify with: docker exec maple-airflow-scheduler airflow connections list"
