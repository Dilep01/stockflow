#!/bin/sh
set -eu
for service in stockflow identity catalog inventory orders fulfillment notification; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v role="sf_$service" -v pass="$POSTGRES_PASSWORD" -v db="$service" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'pass';
CREATE DATABASE :"db" OWNER :"role";
REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"role";
SQL
done
