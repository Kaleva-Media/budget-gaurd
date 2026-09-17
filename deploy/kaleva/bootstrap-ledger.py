#!/usr/bin/python3
"""Owner-only, one-time audited history adoption. Never replay historical SQL."""
import fcntl
import json
import os
import sys
from pathlib import Path
from command import STATE, assert_private, assert_schema, backup, migrate, save_json, sql

if __name__ == "__main__":
    if os.geteuid() != 0 or len(sys.argv) != 2:
        sys.exit("Run as root with an audited repository directory; unavailable to CI.")
    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (STATE / "deploy.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        assert_private()
        assert_schema()
        if sql("select to_regclass('deployment_control.migrations') is not null;") != "f":
            sys.exit("Ledger already exists; refusing bootstrap.")
        recovery = backup("baseline")
        names = migrate(Path(sys.argv[1]).resolve(), baseline=True)
        save_json(STATE / "baseline.json", {"backup": recovery, "adopted": names})
        print(json.dumps({"historical_migrations_adopted_without_replaying": len(names), "backup_restore_verified": True}))
