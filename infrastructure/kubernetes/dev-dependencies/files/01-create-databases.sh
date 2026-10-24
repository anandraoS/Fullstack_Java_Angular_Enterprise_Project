#!/bin/sh
# Runs once on first start of an empty data directory (docker-entrypoint-initdb.d).
# Creates one database + one login role per service. Passwords come from the container environment
# (populated from .env by Compose); nothing is committed. Each role can only connect to its own database.
set -eu

create_db() { # role dbname password
  role="$1"; db="$2"; pw="$3"
  if [ -z "$pw" ]; then echo "missing password for $role" >&2; exit 1; fi
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v role="$role" -v db="$db" -v pw="$pw" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'pw') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'role') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SQL
  echo "created $db / $role"
}

create_db oms_order        order_db        "${ORDER_DB_PASSWORD:-}"
create_db oms_inventory    inventory_db    "${INVENTORY_DB_PASSWORD:-}"
create_db oms_fulfillment  fulfillment_db  "${FULFILLMENT_DB_PASSWORD:-}"
create_db oms_notification notification_db "${NOTIFICATION_DB_PASSWORD:-}"
