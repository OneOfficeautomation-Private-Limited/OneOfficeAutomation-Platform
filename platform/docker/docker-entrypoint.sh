#!/bin/bash
# Starts the Command Center in the container, configured from the environment:
#   OOA_PORT (8720)           the port
#   OOA_MASTER_KEY            the vault key (32 random bytes, Base64); without it a key file is made in /data
#   OOA_DB_URL, OOA_DB_USER   a SQL Server or PostgreSQL database (JDBC address and runtime account)
#   OOA_DB_PASSWORD           that account's password (read from the environment by the server, never on a command line)
# Without OOA_DB_URL the built-in evaluation database in /data is used.
set -euo pipefail
args=(control-room --host 0.0.0.0 --port "${OOA_PORT:-8720}" --data /data)
if [[ -n "${OOA_DB_URL:-}" ]]; then
  args+=(--db-url "$OOA_DB_URL" --db-user "${OOA_DB_USER:-}" --db-password-env OOA_DB_PASSWORD)
fi
exec /opt/ooa/bin/ooa "${args[@]}"
