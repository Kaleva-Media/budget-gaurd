#!/usr/bin/python3
"""Root-owned forced-command deployment controller. No arbitrary remote shell."""
import fcntl
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path, PurePosixPath

sys.path.insert(0, str(Path(__file__).resolve().parent))
from policy import validate_migration

REPO = "edward-kalevamedia/budget-gaurd"
DB = "budget-guard-db"
FUNCTIONS = "budget-guard-edge-functions"
STACK = Path("/home/ubuntu/Databases/supabase-fix/projects/budget-guard")
WEB = Path("/var/www/budget-guard")
STATE = Path("/var/lib/budgetguard-deploy")
FUNCTION_ROOT = STACK / "volumes/functions"
URL = "https://budget.cloudcomms.co.za"
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_EXTRACTED = 128 * 1024 * 1024


def run(args, data=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Never send SQL/user data or secret-bearing diagnostics back to CI.
        (STATE / "last-error.log").write_bytes(result.stderr)
        raise RuntimeError(f"{Path(args[0]).name} failed ({result.returncode}); authorised operator can inspect protected server diagnostics")
    return result.stdout


def sql(text, database="postgres"):
    return run(["docker", "exec", "-i", DB, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database, "-At"], text.encode()).decode().strip()


def fetch(url, headers=None, limit=MAX_ARCHIVE):
    request = urllib.request.Request(url, headers={"User-Agent": "BudgetGuard-deployer", **(headers or {})})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError("Response exceeded deployment size limit")
        return data


def api(path):
    return json.loads(fetch(f"https://api.github.com/repos/{REPO}/{path}"))


def verify_revision(revision):
    if api("commits/main")["sha"] != revision:
        raise ValueError("Deployment is not the current main commit")
    runs = api(f"actions/workflows/ci.yml/runs?head_sha={revision}&event=push&per_page=10")["workflow_runs"]
    if not runs or runs[0]["head_sha"] != revision or runs[0]["head_branch"] != "main" or runs[0]["conclusion"] != "success":
        raise ValueError("Latest main CI run for this exact SHA has not passed")
    jobs = api(f"actions/runs/{runs[0]['id']}/jobs?per_page=100")["jobs"]
    gate = [j for j in jobs if j["name"] == "all-tests"]
    if len(gate) != 1 or gate[0]["conclusion"] != "success":
        raise ValueError("Required all-tests gate did not pass for this SHA")


def safe_extract(data, destination, kind):
    """Never use tar extractall; allow regular bounded files under a known root."""
    if len(data) > MAX_ARCHIVE:
        raise ValueError("Archive too large")
    total = 0
    seen = set()
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = archive.getmembers()
        if len(members) > 3000:
            raise ValueError("Too many archive members")
        source_root = None
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts:
                raise ValueError("Unsafe archive path")
            if member.issym() or member.islnk() or not (member.isdir() or member.isfile()):
                raise ValueError("Archive links/devices are forbidden")
            if kind == "web":
                if path.parts[0] != "dist":
                    raise ValueError("Only the web dist directory may be uploaded")
                relative = Path(*path.parts[1:])
            else:
                source_root = source_root or path.parts[0]
                if path.parts[0] != source_root:
                    raise ValueError("Unexpected source archive root")
                relative = Path(*path.parts[1:])
            if member.isdir():
                continue
            if str(relative) in seen or member.size < 0:
                raise ValueError("Duplicate/invalid archive member")
            seen.add(str(relative))
            total += member.size
            if total > MAX_EXTRACTED:
                raise ValueError("Expanded archive too large")
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as incoming, target.open("wb") as outgoing:
                shutil.copyfileobj(incoming, outgoing)
            target.chmod(0o644)


def assert_private():
    containers = json.loads(run(["docker", "inspect", DB, "budget-guard-pooler", "budget-guard-envoy", "budget-guard-studio"]))
    ips = []
    for container in containers:
        if container["HostConfig"]["NetworkMode"] == "host":
            raise ValueError("Host networking is forbidden")
        bindings = container["HostConfig"].get("PortBindings") or {}
        if container["Name"] in (f"/{DB}", "/budget-guard-studio") and bindings:
            raise ValueError("Database/Studio must have no published ports")
        for entries in bindings.values():
            for entry in entries or []:
                if entry["HostIp"] not in ("127.0.0.1", "::1"):
                    raise ValueError("BudgetGuard service has a public host binding")
        for network in container["NetworkSettings"]["Networks"].values():
            if network.get("IPAddress"):
                ips.append(network["IPAddress"])
    for container in containers:
        for name in container["NetworkSettings"]["Networks"]:
            network = json.loads(run(["docker", "network", "inspect", name]))[0]
            if network["Driver"] != "bridge":
                raise ValueError("Only private Docker bridge networking is supported")
    nginx = run(["nginx", "-T"]).decode()
    if re.search(r"budget-guard-(?:db|pooler)|(?:127\.0\.0\.1|localhost):1242[23]", nginx):
        raise ValueError("Nginx must not proxy BudgetGuard database/pooler")
    if any(re.search(re.escape(ip) + r":(?:5432|6543)\b", nginx) for ip in ips):
        raise ValueError("Nginx exposes a BudgetGuard database upstream")


def assert_schema(database="postgres"):
    result = sql("""
select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
where n.nspname='public' and c.relkind in ('r','p') and not c.relrowsecurity;
select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
where n.nspname='public' and c.relkind='v' and not coalesce(c.reloptions @> array['security_invoker=true'],false);
select count(*) from storage.buckets where id='invoice-documents' and not public;
""", database).splitlines()
    if result != ["0", "0", "1"]:
        raise ValueError("RLS, invoker views, or private invoice bucket check failed")


def backup(revision):
    directory = STATE / "backups"
    directory.mkdir(exist_ok=True)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    path = directory / f"{stamp}-{revision}.dump"
    with path.open("xb") as output:
        subprocess.run(["docker", "exec", DB, "pg_dump", "-U", "postgres", "-d", "postgres", "--format=custom"], stdout=output, stderr=subprocess.PIPE, check=True)
    if path.stat().st_size == 0:
        raise ValueError("Empty recovery dump")
    payload = path.read_bytes()
    run(["docker", "exec", "-i", DB, "pg_restore", "--list"], payload)
    # Exercise restoration inside the existing private DB container, never over production.
    check_db = "budgetguard_restore_" + str(os.getpid())
    run(["docker", "exec", DB, "createdb", "-U", "postgres", check_db])
    try:
        # Supabase's postgres is intentionally not superuser. Restoring extension
        # metadata needs the internal administrator, only in the temporary DB.
        run(["docker", "exec", "-i", DB, "pg_restore", "-U", "supabase_admin", "--no-owner", "--exit-on-error", "-d", check_db], payload)
        assert_schema(check_db)
    finally:
        run(["docker", "exec", DB, "dropdb", "-U", "postgres", check_db])
    checksum = hashlib.sha256(payload).hexdigest()
    path.with_suffix(".sha256").write_text(checksum + "\n")
    return {"path": str(path), "sha256": checksum, "restore_verified": True}


def migration_files(source):
    files = sorted((source / "supabase/migrations").glob("*.sql"))
    versions = [p.name[:14] for p in files]
    if not files or len(set(versions)) != len(versions) or any(not re.fullmatch(r"\d{14}_[a-z0-9_]+\.sql", p.name) for p in files):
        raise ValueError("Invalid/duplicate migration versions")
    return files


def migrate(source, baseline=False):
    files = migration_files(source)
    if baseline:
        approved = json.loads((source / ".github/migration-baseline.json").read_text())
        if set(approved) != {p.name for p in files}:
            raise ValueError("Bootstrap must contain exactly the audited historical migrations")
        # Catalog evidence is a one-time baseline, not proof of every old statement.
        evidence = sql("""select count(*) from information_schema.tables where table_schema='public' and table_name in
('profiles','devices','accounts','categories','budget_periods','budgets','transactions','planned_items','planned_item_matches','entities','invoice_inboxes','suppliers','supplier_bank_accounts','invoices','invoice_payment_plans');
select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname in
('bootstrap_new_user','assign_legacy_entity_scope','approve_invoice','ensure_invoice_inbox');""").splitlines()
        if evidence != ["15", "4"]:
            raise ValueError("Historical schema baseline evidence does not match")
        for path in files:
            if hashlib.sha256(path.read_bytes()).hexdigest() != approved[path.name]:
                raise ValueError("Historical migration checksum mismatch")
        old = {}
    else:
        rows = sql("select filename || '|' || sha256 from deployment_control.migrations order by filename;")
        old = dict(row.split("|") for row in rows.splitlines() if row)
        if set(old) - {p.name for p in files}:
            raise ValueError("An applied migration is absent from the revision")
    pending = []
    for path in files:
        checksum = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name in old:
            if old[path.name] != checksum:
                raise ValueError("An applied migration has been modified")
            continue
        if not baseline:
            if old and path.name < max(old):
                raise ValueError("An out-of-order migration is forbidden")
            validate_migration(path.read_text())
        pending.append((path, checksum))
    statements = ["begin; set local lock_timeout='10s'; set local statement_timeout='120s'; select pg_advisory_xact_lock(783491234);"]
    if baseline:
        statements.append("""create schema deployment_control;
revoke all on schema deployment_control from public,anon,authenticated;
create table deployment_control.migrations(filename text primary key,sha256 text not null,applied_at timestamptz not null default now(),baseline boolean not null);
revoke all on all tables in schema deployment_control from public,anon,authenticated;""")
    for path, checksum in pending:
        if not baseline:
            statements.append(path.read_text())
        statements.append(f"insert into deployment_control.migrations(filename,sha256,baseline) values ('{path.name}','{checksum}',{str(baseline).lower()});")
    statements.extend(["notify pgrst,'reload schema';", "commit;"])
    sql("\n".join(statements))
    return [path.name for path, _ in pending]


def atomic_link(target, link):
    temporary = link.with_name(link.name + ".next")
    if temporary.exists() or temporary.is_symlink():
        temporary.unlink()
    temporary.symlink_to(target)
    temporary.replace(link)


def activate(state):
    atomic_link(state["web"], WEB / "current")
    atomic_link(state["function"], FUNCTION_ROOT / "invoice-ingest")
    run(["docker", "restart", FUNCTIONS])


def smoke(revision):
    for attempt in range(12):
        try:
            if json.loads(fetch(URL + "/version.json", limit=4096))["revision"] != revision:
                raise ValueError("Public web revision does not match deployment")
            page = fetch(URL + "/", limit=1024 * 1024).decode()
            assets = re.findall(r'(?:src|href)="(/assets/[^"?#]+)"', page)
            if not assets:
                raise ValueError("Web build has no assets")
            for asset in assets:
                if not fetch(URL + asset, limit=16 * 1024 * 1024):
                    raise ValueError("Empty web asset")
            # Public key is read only in memory; never logged or embedded in manifests.
            settings = dict(line.split("=", 1) for line in (STACK / ".env").read_text().splitlines() if line and not line.startswith("#") and "=" in line)
            key = settings["SUPABASE_PUBLISHABLE_KEY"].strip('"\'')
            health = json.loads(fetch(URL + "/auth/v1/health", {"apikey": key}, limit=8192))
            if not health:
                raise ValueError("Authentication health check failed")
            assert_private()
            assert_schema()
            return
        except Exception:
            if attempt == 11:
                raise
            time.sleep(2)


def snapshot_initial():
    current = STATE / "current.json"
    if current.exists():
        return json.loads(current.read_text())
    target = FUNCTION_ROOT / "invoice-ingest"
    snapshots = FUNCTION_ROOT / ".budgetguard-releases"
    snapshots.mkdir(exist_ok=True)
    original = snapshots / "before-automation"
    if original.exists():
        raise ValueError("Initial function snapshot already exists without deployment state")
    shutil.copytree(target, original)
    target.rename(FUNCTION_ROOT / "invoice-ingest.before-automation")
    function = ".budgetguard-releases/before-automation"
    atomic_link(function, target)
    state = {"revision": "before-automation", "web": str((WEB / "current").resolve()), "function": function, "previous": None}
    current.write_text(json.dumps(state))
    return state


def deploy(revision, upload):
    verify_revision(revision)
    assert_private()
    assert_schema()
    directory = STATE / "releases" / revision
    if directory.exists():
        manifest_path = directory / "manifest.json"
        if manifest_path.is_file() and json.loads(manifest_path.read_text()).get("status") == "healthy" and json.loads((STATE / "current.json").read_text())["revision"] == revision:
            smoke(revision)
            print(json.dumps({"already_deployed": revision, "smoke_verified": True}))
            return
        raise ValueError("Release already exists; use rollback rather than overwriting it")
    directory.mkdir(parents=True)
    source = directory / "source"
    source.mkdir()
    safe_extract(fetch(f"https://codeload.github.com/{REPO}/tar.gz/{revision}"), source, "source")
    staged_web = directory / "web"
    staged_web.mkdir()
    safe_extract(upload, staged_web, "web")
    if not (staged_web / "index.html").is_file():
        raise ValueError("Missing built web index")
    if json.loads((staged_web / "version.json").read_text())["revision"] != revision:
        raise ValueError("Uploaded build revision does not match")
    recovery = backup(revision)  # Mandatory and restore-tested BEFORE any ledger/schema mutation.
    previous = snapshot_initial()
    manifest = {"revision": revision, "backup": recovery, "previous": previous, "status": "backed_up"}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2))
    applied = migrate(source)
    manifest.update({"migrations": applied, "status": "migrated"})
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2))
    assert_schema()
    web_release = WEB / "releases" / revision
    if web_release.exists():
        raise ValueError("Web release already exists")
    shutil.copytree(staged_web, web_release)
    for path in [web_release, *web_release.rglob("*")]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    function_release = FUNCTION_ROOT / ".budgetguard-releases" / revision
    shutil.copytree(source / "supabase/functions/invoice-ingest", function_release)
    state = {"revision": revision, "web": str(web_release), "function": f".budgetguard-releases/{revision}", "previous": previous}
    try:
        verify_revision(revision)  # A superseded run must not activate.
        activate(state)
        smoke(revision)
    except Exception:
        activate(previous)
        manifest["status"] = "failed_code_rolled_back"
        (directory / "manifest.json").write_text(json.dumps(manifest, indent=2))
        raise
    (STATE / "current.json").write_text(json.dumps(state, indent=2))
    manifest["status"] = "healthy"
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps({"deployed": revision, "backup_restore_verified": True, "migration_count": len(applied)}))


def rollback(target):
    assert_private()
    current = json.loads((STATE / "current.json").read_text())
    if target == "previous":
        state = current["previous"]
    else:
        manifest = json.loads((STATE / "releases" / target / "manifest.json").read_text())
        if manifest["status"] != "healthy":
            raise ValueError("Only an already-healthy release may be selected")
        state = {"revision": target, "web": str(WEB / "releases" / target), "function": f".budgetguard-releases/{target}", "previous": current}
    if not state:
        raise ValueError("No previous release exists")
    activate(state)
    try:
        if state["revision"] != "before-automation":
            smoke(state["revision"])
        else:
            fetch(URL + "/", limit=1024 * 1024)
            assert_schema()
    except Exception:
        activate(current)
        raise
    (STATE / "current.json").write_text(json.dumps(state, indent=2))
    print(json.dumps({"rolled_back_to": state["revision"], "database": "compatible additive schema retained; no data restored"}))


def main():
    os.umask(0o077)
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    # One lock shared by deploy, rollback, baseline, and verification.
    with (STATE / "deploy.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        command = sys.argv[1] if len(sys.argv) == 2 else ""
        match = re.fullmatch(r"(deploy|rollback) ([0-9a-f]{40}|previous)|verify", command)
        if not match:
            raise ValueError("Forced command permits only deploy SHA, rollback SHA/previous, or verify")
        if command == "verify":
            assert_private()
            assert_schema()
            print("BudgetGuard private-port/RLS/Storage checks passed")
        elif match[1] == "deploy":
            if match[2] == "previous":
                raise ValueError("Deploy requires a full commit SHA")
            deploy(match[2], sys.stdin.buffer.read(MAX_ARCHIVE + 1))
        else:
            rollback(match[2])


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Deployment refused/failed: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
