#!/bin/sh
set -eu

backup_dir="/home/ubuntu/backups/budget-guard"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
final_path="$backup_dir/budget-guard-$stamp.dump"
temp_path="$backup_dir/.budget-guard-$stamp.dump.tmp"

umask 077
install -d -m 700 "$backup_dir"

cleanup() {
  rm -f "$temp_path"
}
trap cleanup EXIT HUP INT TERM

docker exec budget-guard-db pg_dump -U postgres -d postgres --format=custom > "$temp_path"
test -s "$temp_path"
mv "$temp_path" "$final_path"
chmod 600 "$final_path"

# Keep fourteen daily recovery points on this host.
find "$backup_dir" -type f -name 'budget-guard-*.dump' -mtime +13 -delete

echo "$final_path"
