import importlib.util
import base64
import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from policy import scan_sensitive, validate_migration

spec = importlib.util.spec_from_file_location("controller", HERE / "kaleva/command.py")
controller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controller)


def archive(name="dist/index.html", kind=None, link=None):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as tar:
        member = tarfile.TarInfo(name)
        if kind:
            member.type = kind
            member.linkname = link or ""
            tar.addfile(member)
        else:
            member.size = 4
            tar.addfile(member, io.BytesIO(b"test"))
    return output.getvalue()


class ControllerTests(unittest.TestCase):
    def test_valid_web_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            controller.safe_extract(archive(), Path(directory), "web")
            self.assertEqual((Path(directory) / "index.html").read_text(), "test")

    def test_archive_traversal_links_wrong_root_and_size_rejected(self):
        cases = [archive("../escape"), archive("/absolute"), archive("secrets/.env"), archive(kind=tarfile.SYMTYPE, link="/etc/passwd"), archive(kind=tarfile.LNKTYPE, link="../escape")]
        with tempfile.TemporaryDirectory() as directory:
            for payload in cases:
                with self.subTest(payload=payload[:20]), self.assertRaises(ValueError):
                    controller.safe_extract(payload, Path(directory), "web")
            with patch.object(controller, "MAX_EXTRACTED", 3), self.assertRaises(ValueError):
                controller.safe_extract(archive(), Path(directory), "web")

    def test_additive_migration_allowed(self):
        validate_migration("alter table public.accounts add column label text; create index accounts_label_idx on public.accounts(label);")

    def test_server_credentials_cannot_enter_public_bundle(self):
        def jwt(role):
            payload = base64.urlsafe_b64encode(json.dumps({"role": role}).encode()).rstrip(b"=")
            return b"eyJhbGciOiJIUzI1NiJ9." + payload + b".syntheticSignature"
        scan_sensitive(jwt("anon"))
        for data in [jwt("service_role"), jwt("supabase_admin"), b"sb_" + b"secret_" + b"a" * 24, b"-----BEGIN " + b"PRIVATE KEY-----"]:
            with self.assertRaises(ValueError):
                scan_sensitive(data)

    def test_unsafe_migrations_rejected(self):
        examples = ["drop table public.accounts;", "truncate public.accounts;", "delete from public.accounts;", "commit;", "select 1; COMMIT;", "set role supabase_admin;", "alter user postgres superuser;", "\\! id", "copy t to program 'id';", "alter table t disable row level security;", "create or replace function f() returns void;", "alter table t alter column c type int;", "alter table t add column c text not null;", "grant all on t to anon;", "select * from deployment_control.migrations;", "create index concurrently i on t(c);"]
        for text in examples:
            with self.subTest(sql=text), self.assertRaises(ValueError):
                validate_migration(text)

    def test_public_binding_and_nginx_proxy_rejected(self):
        good = [{"Name": "/budget-guard-db", "HostConfig": {"NetworkMode": "bridge", "PortBindings": {}}, "NetworkSettings": {"Networks": {}}}]
        with patch.object(controller, "run", side_effect=[json.dumps(good).encode(), b"server {}"]):
            controller.assert_private()
        public = json.loads(json.dumps(good))
        public[0]["HostConfig"]["PortBindings"] = {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]}
        with patch.object(controller, "run", return_value=json.dumps(public).encode()), self.assertRaises(ValueError):
            controller.assert_private()
        with patch.object(controller, "run", side_effect=[json.dumps(good).encode(), b"proxy_pass 127.0.0.1:12422;"]), self.assertRaises(ValueError):
            controller.assert_private()

    def test_exact_sha_latest_successful_ci_required(self):
        sha = "a" * 40
        for runs in [[], [{"head_sha": sha, "head_branch": "main", "conclusion": "failure"}], [{"head_sha": "b" * 40, "head_branch": "main", "conclusion": "success"}]]:
            with patch.object(controller, "api", side_effect=[{"sha": sha}, {"workflow_runs": runs}]), self.assertRaises(ValueError):
                controller.verify_revision(sha)
        with patch.object(controller, "api", side_effect=[{"sha": sha}, {"workflow_runs": [{"id": 1, "head_sha": sha, "head_branch": "main", "conclusion": "success"}]}, {"jobs": [{"name": "all-tests", "conclusion": "success"}]}]):
            controller.verify_revision(sha)

    def test_backup_failure_prevents_migrations(self):
        sha = "a" * 40
        with tempfile.TemporaryDirectory() as directory:
            def extract(payload, destination, kind):
                if kind == "web":
                    (destination / "index.html").write_text("test")
                    (destination / "version.json").write_text(json.dumps({"revision": sha}))
            with patch.object(controller, "STATE", Path(directory)), patch.object(controller, "verify_revision"), patch.object(controller, "assert_private"), patch.object(controller, "assert_schema"), patch.object(controller, "fetch", return_value=b"archive"), patch.object(controller, "safe_extract", side_effect=extract), patch.object(controller, "backup", side_effect=ValueError("backup failed")), patch.object(controller, "migrate") as migrate:
                with self.assertRaises(ValueError):
                    controller.deploy(sha, b"web")
                migrate.assert_not_called()

    def test_restore_failure_cleans_up_check_database(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(controller, "STATE", Path(directory)):
            def dump(args, **kwargs):
                kwargs["stdout"].write(b"backup")
            with patch.object(controller.subprocess, "run", side_effect=dump), patch.object(controller, "run", side_effect=[b"list", b"created", ValueError("restore failed"), b"dropped"]) as run:
                with self.assertRaises(ValueError):
                    controller.backup("a" * 40)
                self.assertIn("dropdb", run.call_args_list[-1].args[0])

    def test_pending_migrations_and_ledger_one_transaction(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            migrations = source / "supabase/migrations"
            migrations.mkdir(parents=True)
            (migrations / "20260917010000_add_label.sql").write_text("alter table public.accounts add column label text;")
            with patch.object(controller, "sql", side_effect=["", ""]) as sql:
                self.assertEqual(len(controller.migrate(source)), 1)
                text = sql.call_args.args[0]
                self.assertTrue(text.startswith("begin;"))
                self.assertTrue(text.rstrip().endswith("commit;"))
                self.assertIn("insert into deployment_control.migrations", text)
                self.assertLess(text.index("Deployment security invariant"), text.index("commit;"))

    def test_failed_smoke_reactivates_previous_release(self):
        sha = "a" * 40
        previous = {"revision": "previous", "web": "old", "function": "old"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def extract(payload, destination, kind):
                if kind == "web":
                    (destination / "index.html").write_text("test")
                    (destination / "version.json").write_text(json.dumps({"revision": sha}))
                else:
                    (destination / "supabase/functions/invoice-ingest").mkdir(parents=True)
            with patch.object(controller, "STATE", root / "state"), patch.object(controller, "WEB", root / "web"), patch.object(controller, "FUNCTION_ROOT", root / "functions"), patch.object(controller, "verify_revision"), patch.object(controller, "assert_private"), patch.object(controller, "assert_schema"), patch.object(controller, "fetch", return_value=b"source"), patch.object(controller, "safe_extract", side_effect=extract), patch.object(controller, "backup", return_value={"restore_verified": True}), patch.object(controller, "snapshot_initial", return_value=previous), patch.object(controller, "migrate", return_value=[]), patch.object(controller, "activate") as activate, patch.object(controller, "smoke", side_effect=ValueError("bad health")):
                with self.assertRaises(ValueError):
                    controller.deploy(sha, b"web")
                self.assertEqual(activate.call_count, 2)
                self.assertEqual(activate.call_args.args[0], previous)
                manifest = json.loads((root / "state/releases" / sha / "manifest.json").read_text())
                self.assertEqual(manifest["status"], "failed_code_rolled_back")

    def test_database_rollback_never_restores_or_migrates(self):
        old = {"revision": "before-automation", "web": "old", "function": "old"}
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            (state / "current.json").write_text(json.dumps({"revision": "new", "previous": old}))
            with patch.object(controller, "STATE", state), patch.object(controller, "assert_private"), patch.object(controller, "assert_schema"), patch.object(controller, "activate"), patch.object(controller, "fetch", return_value=b"page"), patch.object(controller, "backup") as backup, patch.object(controller, "migrate") as migrate:
                controller.rollback("previous")
                backup.assert_not_called()
                migrate.assert_not_called()

    def test_atomic_state_write_preserves_old_state_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "current.json"
            path.write_text('{"revision":"old"}')
            with patch.object(controller.os, "fsync", side_effect=OSError("disk failure")), self.assertRaises(OSError):
                controller.save_json(path, {"revision": "new"})
            self.assertEqual(json.loads(path.read_text())["revision"], "old")
            self.assertEqual(list(Path(directory).glob(".state-*")), [])


if __name__ == "__main__":
    unittest.main()
