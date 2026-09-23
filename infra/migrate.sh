#!/bin/sh
set -eu

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"
: "${WORKER_DB_PASSWORD:?WORKER_DB_PASSWORD is required}"

export PGPASSWORD="$DB_PASSWORD"
export PGSSLMODE="${DB_SSLMODE:-require}"

attempt=0
until pg_isready --host "$DB_HOST" --port "$DB_PORT" --username "$DB_USERNAME" --dbname "$DB_NAME" --quiet; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 90 ]; then
    echo "Database did not become reachable within three minutes." >&2
    exit 1
  fi

  echo "Waiting for database at $DB_HOST:$DB_PORT..." >&2
  sleep 2
done

exec psql \
  --host "$DB_HOST" \
  --port "$DB_PORT" \
  --username "$DB_USERNAME" \
  --dbname "$DB_NAME" \
  --no-password \
  --set ON_ERROR_STOP=1 \
  --set "app_db_password=$APP_DB_PASSWORD" \
  --set "worker_db_password=$WORKER_DB_PASSWORD" \
  --file /migrations/001_initial_schema.sql
