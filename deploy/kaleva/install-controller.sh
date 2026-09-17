#!/bin/bash
# Owner-operated installation, NOT executable through the CI credential.
set -euo pipefail
test "$(id -u)" = 0
test "$#" = 2 || { echo 'Usage: install-controller.sh audited-repo-directory public-key-file' >&2; exit 1; }
source_dir=$(realpath "$1")
key_file=$(realpath "$2")
test -f "$source_dir/deploy/kaleva/command.py"
test -f "$source_dir/deploy/policy.py"
key=$(awk 'NF>=2 {print $1 " " $2}' "$key_file")
[[ "$key" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+$ ]] || { echo 'One Ed25519 public key required' >&2; exit 1; }
if ! id budgetguard-deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/sh budgetguard-deploy
fi
# Home and authorized_keys are root-owned: CI cannot replace its forced command.
chown root:root /home/budgetguard-deploy
chmod 755 /home/budgetguard-deploy
install -d -o root -g root -m 755 /opt/budgetguard-deploy /home/budgetguard-deploy/.ssh
if [ -f /opt/budgetguard-deploy/command.py ]; then
  saved=$(mktemp -d /opt/budgetguard-deploy/operator-backup.XXXXXXXX)
  for previous in command.py policy.py entry.sh bootstrap-ledger.py; do
    if [ -f "/opt/budgetguard-deploy/$previous" ]; then
      cp -p "/opt/budgetguard-deploy/$previous" "$saved/$previous"
    fi
  done
fi
install -o root -g root -m 755 "$source_dir/deploy/kaleva/command.py" /opt/budgetguard-deploy/command.py
install -o root -g root -m 644 "$source_dir/deploy/policy.py" /opt/budgetguard-deploy/policy.py
install -o root -g root -m 755 "$source_dir/deploy/kaleva/entry.sh" /opt/budgetguard-deploy/entry.sh
install -o root -g root -m 755 "$source_dir/deploy/kaleva/bootstrap-ledger.py" /opt/budgetguard-deploy/bootstrap-ledger.py
printf 'restrict,command="/opt/budgetguard-deploy/entry.sh" %s\n' "$key" > /home/budgetguard-deploy/.ssh/authorized_keys
chmod 644 /home/budgetguard-deploy/.ssh/authorized_keys
printf 'budgetguard-deploy ALL=(root) NOPASSWD: /opt/budgetguard-deploy/command.py *\n' > /etc/sudoers.d/budgetguard-deploy
chmod 440 /etc/sudoers.d/budgetguard-deploy
visudo -cf /etc/sudoers.d/budgetguard-deploy
install -d -o root -g root -m 700 /var/lib/budgetguard-deploy
/opt/budgetguard-deploy/command.py verify
echo 'Restricted deployment controller installed; bootstrap ledger separately after backup.'
