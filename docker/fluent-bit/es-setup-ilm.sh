#!/bin/bash
# ES Index Template + ILM Policy Bootstrap
# Run once after ES is up: bash scripts/es-setup-ilm.sh

ES_HOST="${ES_HOST:-localhost:9200}"

echo "Creating ILM policy (30-day retention)..."
curl -s -X PUT "${ES_HOST}/_ilm_policy/logs-retention" -H 'Content-Type: application/json' -d '
{
  "policy": {
    "phases": {
      "hot": {
        "min_age": "0ms",
        "actions": {
          "rollover": {
            "max_age": "7d",
            "max_primary_shard_size": "50gb"
          },
          "set_priority": {
            "priority": 100
          }
        }
      },
      "delete": {
        "min_age": "30d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
'

echo ""
echo "Creating index template for logs-*..."
curl -s -X PUT "${ES_HOST}/_index_template/logs-template" -H 'Content-Type: application/json' -d '
{
  "index_patterns": ["logs-*"],
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "index.lifecycle.name": "logs-retention",
      "index.lifecycle.rollover_alias": "logs"
    },
    "mappings": {
      "properties": {
        "timestamp": { "type": "date" },
        "level": { "type": "keyword" },
        "service": { "type": "keyword" },
        "logger_name": { "type": "keyword" },
        "message": { "type": "text" },
        "runId": { "type": "keyword" },
        "chunkId": { "type": "keyword" },
        "kafkaTopic": { "type": "keyword" },
        "thread": { "type": "keyword" },
        "stack_trace": { "type": "text" }
      }
    }
  }
}
'

echo ""
echo "Done. Verify with:"
echo "  curl -s ${ES_HOST}/_ilm/policy/logs-retention | jq"
echo "  curl -s ${ES_HOST}/_index_template/logs-template | jq"
