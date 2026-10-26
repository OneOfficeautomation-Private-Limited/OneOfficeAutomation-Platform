#!/bin/bash
# Starts the Command Center in the container, configured from the environment:
#   OOA_PORT (8720)           the port
#   OOA_MASTER_KEY            the vault key (32 random bytes, Base64); without it a key file is made in /data
#   OOA_DB_URL, OOA_DB_USER   a SQL Server or PostgreSQL database (JDBC address and runtime account)
#   OOA_DB_PASSWORD           that account's password (read from the environment by the server, never on a command line)
# Without OOA_DB_URL the built-in evaluation database in /data is used.
# With arguments it runs that ooa command instead, on the same data and database, for example:
#   docker run --rm -v ooa-data:/data -v /backups:/backups -e OOA_MASTER_KEY=... -e OOA_DB_URL=... ooa-command-center backup restore /backups/FILE
set -euo pipefail
db=()
if [[ -n "${OOA_DB_URL:-}" ]]; then
  db=(--db-url "$OOA_DB_URL" --db-user "${OOA_DB_USER:-}" --db-password-env OOA_DB_PASSWORD)
fi
if [[ $# -gt 0 ]]; then
  case "$1" in
    backup) exec /opt/ooa/bin/ooa "$@" --data /data "${db[@]}" ;;
    *) exec /opt/ooa/bin/ooa "$@" ;;
  esac
fi
exec /opt/ooa/bin/ooa control-room --host 0.0.0.0 --port "${OOA_PORT:-8720}" --data /data "${db[@]}"
