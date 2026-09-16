#!/bin/sh
set -eu

target_dir="/home/ubuntu/Databases/supabase-fix/projects/budget-guard"
public_url="https://budget.cloudcomms.co.za"

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 /path/to/pinned-supabase/docker" >&2
  exit 64
fi

source_dir="$1"
test -f "$source_dir/docker-compose.yml"
test -f "$source_dir/.env.example"
test ! -e "$target_dir"

umask 077
install -d -m 700 "$target_dir"
cp -a "$source_dir/." "$target_dir/"
cd "$target_dir"
cp .env.example .env

# Generate a unique database password, signing keys, client/server API keys,
# Dashboard password, and internal encryption keys without printing them.
sh ./utils/generate-keys.sh --update-env >/dev/null
sh ./utils/add-new-auth-keys.sh --update-env >/dev/null

set_env() {
  key="$1"
  value="$2"
  awk -v key="$key" -v value="$value" '
    BEGIN { found = 0 }
    index($0, key "=") == 1 { print key "=" value; found = 1; next }
    { print }
    END { if (!found) print key "=" value }
  ' .env > .env.next
  mv .env.next .env
}

set_env COMPOSE_PROJECT_NAME budget-guard
set_env SUPABASE_PUBLIC_URL "$public_url"
set_env API_EXTERNAL_URL "$public_url/auth/v1"
set_env SITE_URL "$public_url"
set_env ADDITIONAL_REDIRECT_URLS "$public_url"
set_env STUDIO_DEFAULT_ORGANIZATION BudgetGuard
set_env STUDIO_DEFAULT_PROJECT BudgetGuard
set_env DASHBOARD_USERNAME budgetguard-admin
set_env ENABLE_EMAIL_SIGNUP true
set_env ENABLE_EMAIL_AUTOCONFIRM true
set_env ENABLE_PHONE_SIGNUP false
set_env ENABLE_PHONE_AUTOCONFIRM false
set_env ENABLE_ANONYMOUS_USERS false
set_env DISABLE_SIGNUP false
set_env FUNCTIONS_VERIFY_JWT true
set_env API_GW_HTTP_PORT 12421
set_env POOLER_SESSION_HOST_PORT 12422
set_env POOLER_TRANSACTION_HOST_PORT 12423
set_env POOLER_TENANT_ID budget-guard

# The upstream compose file uses global container names and public host binds.
# Namespace this installation and expose its entry points to local Nginx only.
sed -i \
  -e 's/container_name: supabase-/container_name: budget-guard-/g' \
  -e 's/container_name: realtime-dev\.supabase-realtime/container_name: budget-guard-realtime/' \
  -e 's|${API_GW_HTTP_PORT:-${KONG_HTTP_PORT:-8000}}:8000/tcp|127.0.0.1:${API_GW_HTTP_PORT:-${KONG_HTTP_PORT:-8000}}:8000/tcp|' \
  -e 's|${POSTGRES_PORT}:5432|127.0.0.1:${POOLER_SESSION_HOST_PORT:-12422}:5432|' \
  -e 's|${POOLER_PROXY_PORT_TRANSACTION}:6543|127.0.0.1:${POOLER_TRANSACTION_HOST_PORT:-12423}:6543|' \
  docker-compose.yml

chmod 600 .env
# The upstream generators leave a prior `.env` copy behind. It can contain
# superseded credentials, so do not retain that extra secret-bearing file.
rm -f .env.old

docker compose config --quiet
echo "BudgetGuard Supabase configuration prepared at $target_dir"
