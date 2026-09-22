"""Conservative checks, not a SQL sandbox or a substitute for review."""
import hashlib
import base64
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECRET = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|sb_secret_[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|AKIA[A-Z0-9]{16}")


def scan_sensitive(data):
    if SECRET.search(data):
        raise ValueError("Possible private credential")
    for token in re.finditer(rb"eyJ[A-Za-z0-9_-]+\.([A-Za-z0-9_-]+)\.[A-Za-z0-9_-]+", data):
        try:
            payload = token[1]
            claims = json.loads(base64.urlsafe_b64decode(payload + b"=" * (-len(payload) % 4)))
        except (ValueError, UnicodeError):
            continue
        if isinstance(claims, dict) and claims.get("role") in ("service_role", "supabase_admin"):
            raise ValueError("Privileged server JWT must not be committed or published")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_migration(sql):
    # Deliberately fail closed, including suspicious text in function bodies.
    checked_sql = re.sub(
        r"\bfor\s+each\s+row\s+execute\s+function\s+[a-z_][a-z0-9_.]*\s*\([^;]*?\)",
        "",
        sql,
        flags=re.I,
    )
    checked_sql = re.sub(
        r"\bgrant\s+execute\s+on\s+function\s+[a-z_][a-z0-9_.]*\s*\([^;]*?\)\s+to\s+authenticated\b",
        "",
        checked_sql,
        flags=re.I,
    )
    if re.search(r"\bexecute\b", checked_sql, re.I):
        raise ValueError("Migration rejected: dynamic or unscoped execution. Use a fixed trigger function or an authenticated-only function grant.")
    rules = {
        "destructive SQL": r"\b(drop|truncate)\b|\bdelete\s+from\b",
        "transaction escape": r"\b(commit|rollback|start\s+transaction)\b|\bbegin\s*;",
        "psql command": r"(?m)^\s*\\",
        "privilege or OS escape": r"\b(copy|security\s+definer|dblink|pg_read_file|pg_write_file|lo_export|lo_import|alter\s+system|(?:create|alter)\s+(?:role|user|database|extension|language)|set\s+(?:role|session)|reset\s+role)\b",
        "deployment ledger access": r"\bdeployment_control\b",
        "weakened RLS": r"\bdisable\s+row\s+level\s+security\b|\bbypassrls\b",
        "contract replacement": r"\bcreate\s+or\s+replace\b|\balter\s+column\b|\brename\b",
        "nontransactional migration": r"\bconcurrently\b|\bvacuum\b",
        "anonymous grants": r"\bgrant\b[^;]*\bto\s+(public|anon)\b",
        "anonymous policy": r"\bcreate\s+policy\b[^;]*\bto\s+(public|anon)\b",
    }
    for name, pattern in rules.items():
        if re.search(pattern, sql, re.I):
            raise ValueError(f"Migration rejected: {name}. Use an additive migration or a separately reviewed maintenance procedure.")
    if re.search(r"\balter\s+table\b[^;]*\badd\b[^;]*\bnot\s+null\b", sql, re.I):
        raise ValueError("New columns on existing tables must initially be nullable.")


def check(root=ROOT, base=None):
    baseline_path = root / ".github/migration-baseline.json"
    baseline = json.loads(baseline_path.read_text())
    migrations = {p.name: p for p in (root / "supabase/migrations").glob("*.sql")}
    if len({name[:14] for name in migrations}) != len(migrations):
        raise ValueError("Duplicate migration versions")
    for name, checksum in baseline.items():
        if name not in migrations or digest(migrations[name]) != checksum:
            raise ValueError(f"Historical migration changed or missing: {name}")
    if base:
        old_baseline = subprocess.run(["git", "show", f"{base}:.github/migration-baseline.json"], cwd=root, capture_output=True)
        if old_baseline.returncode == 0 and json.loads(old_baseline.stdout) != baseline:
            raise ValueError("The approved migration baseline cannot be changed by a PR.")
        previous = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", base, "supabase/migrations"], cwd=root, text=True).splitlines()
        for name in previous:
            old = subprocess.check_output(["git", "show", f"{base}:{name}"], cwd=root)
            path = root / name
            if not path.exists() or path.read_bytes() != old:
                raise ValueError(f"Applied/main migration cannot be edited or removed: {name}")
        oldest_new = max((Path(p).name[:14] for p in previous), default="")
    else:
        oldest_new = max(name[:14] for name in baseline)
    for name, path in migrations.items():
        if not re.fullmatch(r"\d{14}_[a-z0-9_]+\.sql", name):
            raise ValueError(f"Invalid migration filename: {name}")
        if name not in baseline:
            validate_migration(path.read_text())
            if base and not subprocess.run(["git", "cat-file", "-e", f"{base}:supabase/migrations/{name}"], cwd=root, stderr=subprocess.DEVNULL).returncode:
                continue
            if name[:14] <= oldest_new:
                raise ValueError(f"New migration must sort after main's migrations: {name}")
    # Narrow secret scan. Dedicated security review is still required.
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    for name in filter(None, tracked):
        path = root / name
        if path.is_file():
            try:
                scan_sensitive(path.read_bytes())
            except ValueError as error:
                raise ValueError(f"Possible credential in tracked file: {name}") from error


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    args = parser.parse_args()
    check(base=args.base)
    print("Deployment policy checks passed.")
