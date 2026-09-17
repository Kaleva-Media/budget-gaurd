#!/bin/sh
# Root-owned forced-command entry. Arguments remain data, never shell code.
exec /usr/bin/sudo -n /opt/budgetguard-deploy/command.py "${SSH_ORIGINAL_COMMAND:-}"
