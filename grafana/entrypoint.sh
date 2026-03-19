#!/bin/sh
set -e

mkdir -p /var/log/inventory /tmp/alloy-data

if [ -n "${GCLOUD_RW_API_KEY:-}" ]; then
  if [ -n "${GCLOUD_HOSTED_METRICS_URL:-}" ] && [ -n "${GCLOUD_HOSTED_METRICS_ID:-}" ] &&
     [ -n "${GCLOUD_HOSTED_LOGS_URL:-}" ] && [ -n "${GCLOUD_HOSTED_LOGS_ID:-}" ]; then
    export GCLOUD_FM_COLLECTOR_ID="${GCLOUD_FM_COLLECTOR_ID:-inventory-api}"
    export LOGGING_FILE_NAME="${LOGGING_FILE_NAME:-/var/log/inventory/application.log}"
    /usr/local/bin/alloy run --storage.path=/tmp/alloy-data /etc/alloy/config.alloy &
  else
    echo "WARN: GCLOUD_RW_API_KEY is set but Grafana Cloud metrics/logs env vars are incomplete; Alloy not started. See docs/GRAFANA_CLOUD.md" >&2
  fi
fi

exec java -jar /app/app.jar
