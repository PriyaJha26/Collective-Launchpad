#!/usr/bin/env bash
# Local development: runs the agent (Python, :8000) and the web service (Java, :8080) against a
# local PostgreSQL database named "launchpad". Ctrl-C stops both. For the server, use
# docker compose instead (see README.md).
#
# Optional settings go in .env.local (git-ignored), e.g. ANTHROPIC_API_KEY=... to use real Claude.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f .env.local ]]; then
  set -a; source .env.local; set +a
fi

export INTERNAL_TOKEN="${INTERNAL_TOKEN:-$(openssl rand -hex 24)}"
export DOMAIN="${DOMAIN:-localhost}" PUBLIC_SCHEME="${PUBLIC_SCHEME:-http}" PUBLIC_PORT="${PUBLIC_PORT:-8080}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/launchpad}" DB_USER="${DB_USER:-launchpad}"
export UPLOADS_DIR="${UPLOADS_DIR:-$PWD/web/data/uploads}"
export MAIL_FROM="${MAIL_FROM:-hello@localhost}"
export ADMIN_EMAIL="${ADMIN_EMAIL:-admin@localhost}"
export SEED_SAMPLE_SHOP="${SEED_SAMPLE_SHOP:-true}"
export THYMELEAF_CACHE=false
export PGGSSENCMODE=disable   # psql can hang negotiating GSSAPI on some corporate machines
export WEB_INTERNAL_URL="http://localhost:${PUBLIC_PORT}"
export AGENT_URL="http://localhost:8000"

if ! pg_isready -q 2>/dev/null; then
  echo "PostgreSQL is not running on localhost:5432. Start it (e.g. brew services start postgresql@16)."
  exit 1
fi
if ! psql -U "$DB_USER" -d launchpad -Atc "select 1" >/dev/null 2>&1; then
  echo "Creating local database 'launchpad'…"
  psql -d postgres -qc "DO \$\$BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='$DB_USER') THEN CREATE ROLE $DB_USER LOGIN; END IF; END\$\$;"
  createdb -O "$DB_USER" launchpad
fi

if [[ ! -x agent/.venv/bin/uvicorn ]]; then
  echo "Setting up the agent's Python environment…"
  python3 -m venv agent/.venv
  agent/.venv/bin/pip install -q -r agent/requirements.txt pytest
fi

if [[ ! -f web/target/web.jar ]] || [[ -n "$(find web/src web/pom.xml -newer web/target/web.jar 2>/dev/null | head -1)" ]]; then
  echo "Building the web service…"
  (cd web && mvn -q package -DskipTests)
fi

(cd agent && exec .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000) &
AGENT_PID=$!
trap 'kill $AGENT_PID 2>/dev/null' EXIT
for _ in $(seq 1 30); do
  curl -s -o /dev/null http://127.0.0.1:8000/health && break
  sleep 0.5
done

echo
echo "  Group site:  http://localhost:${PUBLIC_PORT}/"
echo "  Admin:       http://localhost:${PUBLIC_PORT}/admin   (login: ${ADMIN_EMAIL})"
echo "  Shops:       http://<address>.localhost:${PUBLIC_PORT}/   e.g. http://sample.localhost:${PUBLIC_PORT}/"
[[ -z "${ADMIN_PASSWORD:-}" ]] && echo "  The admin password is printed below on first start (look for 'one-time generated password')."
echo
java -jar web/target/web.jar
