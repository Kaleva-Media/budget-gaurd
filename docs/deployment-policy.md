# Merge and production policy

These rules apply to people and agents. Read this before changing workflows,
migrations, deployment code, permissions or production configuration.

## A normal change

1. Start from current `main` on a `codex/<task>` branch. Preserve unrelated work.
2. Implement one scoped change. Add regression tests, including denial cases for
   authentication, ownership, money calculations and document ingestion.
3. Run relevant checks locally; state what could not run. Never skip a failing
   suite, weaken a test or disable a guard just to merge.
4. Open a PR with test evidence, risk, deployment impact and rollback instructions.
5. Wait for `all-tests` on the **latest** commit, an up-to-date branch, resolved
   conversations and one independent approving reviewer, including required
   code-owner approval where applicable. New pushes dismiss old
   approvals. The last pusher must not provide the qualifying approval.
6. Squash merge. No direct pushes, force pushes, self-approval, administrator
   bypasses or temporary removal of protections. Agents must not change repository
   governance without an explicit owner request for that task.
7. A merge triggers CI again on the resulting `main` SHA. Deployment uses that
   exact successful run's artifact, never a PR build or a local developer build.
8. Check the deployment outcome and relevant smoke tests. Update the handoff with
   the tested SHA and actual result. A green build is not a verified deployment.

`all-tests` aggregates domain/web checks and configured build, built-browser
demo/auth smoke tests, Android parser/app unit tests, email tests/type checks and
Worker dry run, Deno checks/tests, isolated database migrations/pgTAP tests, and
deployment-policy/controller tests. Failure, cancellation or a skipped suite
fails the aggregate. Actions and tool versions are pinned; dependency updates
need their own tested review.

## Deployment order and database safety

The production server's root-owned forced-command controller independently
checks current `main` and its latest successful CI gate. The CI account can only
request `deploy <full-sha>`, `rollback <healthy-sha|previous>` or `verify`; it has
no general SSH shell, file-transfer access or Docker access.

Every new deployment follows this order:

1. Verify exact tested SHA and private network/RLS/Storage conditions.
2. Stage bounded web assets and authoritative source fetched by immutable SHA.
3. Create a protected full database dump, checksum it, and successfully restore
   it into a temporary private test database. Failure stops deployment.
4. Apply only pending forward migrations and their checksummed ledger in one
   transaction. Verify RLS/views/private-bucket invariants **before commit**, so
   unsafe schema cannot briefly become live. Never replay or edit applied SQL.
5. Retain previous web/function copies, switch releases, restart the function
   service, and check public revision/assets/auth health plus private-port/RLS
   conditions. A failed activation/health check attempts automatic code rollback.

The historical baseline records the five migrations already present before
automation; an owner-only bootstrap backs up first and adopts their checksums
without executing historical SQL. Do not modify the baseline.

Automated migrations must be additive and compatible with the previous app
version. Drops, truncation, row deletion, contract replacement, column changes,
RLS weakening, anonymous grants and nontransactional SQL fail the conservative
policy check. This is **not** a SQL sandbox or a proof of compatibility: reviewer
judgment and ownership tests remain mandatory. Changes rejected by policy need a
separate owner-approved maintenance plan; do not work around the scanner.

Never run production `db reset`, ad-hoc migration SQL, provisioning, a down
migration or backup restore through the CI account. Do not publish database or
Studio ports, attach public/host networks, or proxy a database through Nginx.
BudgetGuard's pooler/gateway must remain loopback-only; only the HTTPS API is
public. Client keys are publishable, never service-role/database credentials.
All public app tables need RLS, views need `security_invoker=true`, and invoice
documents must remain private. CI packaging and the server reject hidden/key
files and common private credentials/privileged server JWTs in static assets.
This narrow scan is a backstop, not a complete secret-detection system.

## Recovery

Use **Roll back web platform** on `main`, with `previous` or the full SHA of an
already-healthy retained release. The `production-rollback` environment requires
owner approval and shares the deployment lock/concurrency group. Retained web
and invoice-function code are restored and checked; compatible additive schema
and new user writes remain intact.

Database recovery is a different, destructive operation. Prefer a forward fix.
Before restoring a dump, obtain explicit owner authorization, stop writes,
protect the current database and storage files, quantify lost writes, rehearse
the restore, and coordinate app/function/Worker versions. Never imply that an
automatic code rollback reverses migrated data.

Failed release directories are retained as evidence and are not overwritten.
An owner must inspect them before approving a retry/cleanup, or deploy a new
reviewed commit. Re-dispatching an already-current healthy SHA is a smoke-checked
no-op. Keep recovery dumps/releases until the owner approves a retention policy;
monitor free disk space. Deployment dumps currently remain on the same host.
They do not include object-storage bytes, global database roles or off-host
disaster recovery. These limitations must remain visible.

The Cloudflare email Worker is separate from the self-hosted web/function
release. Its tests run in CI, but automated publishing requires a scoped
Cloudflare deployment credential; see the handoff. Until configured and tested,
do not describe its production rollout or real email delivery as automated.
Retain full Worker deployment/version IDs before authorized manual publishing
and use Wrangler rollback; never rotate invoice signing secrets during rollback.

## Enforcement boundaries

| Requirement | Enforcement |
| --- | --- |
| Latest tests and independent review | GitHub protected `main`, Actions-bound `all-tests`, strict freshness, stale/last-push review rules, CODEOWNERS |
| No administrator merge bypass | Branch protection applies to administrators |
| Main-only deployment credentials | Protected GitHub environments, workflow main/event checks, server SHA/CI check |
| Backup before migration | Server controller's successful dump + restore test before ledger/schema mutation |
| Applied SQL immutable/additive | CI policy, baseline checksums, private server ledger, independent review |
| Private DB and invoices | Server checks ports/networks/Nginx/RLS/views/bucket; database denial tests |
| Code rollback | Retained releases, failed-health recovery, approved rollback workflow |
| No arbitrary production shell for CI | Root-owned authorized_keys/controller, forced command and narrow sudo rule |

`AGENTS.md` is operating guidance, not a credential boundary. Normal agents
should receive a **non-admin write-only GitHub identity**, not the owner's admin
identity, general production SSH, service-role key or Docker socket. Repository
owners can still edit governance settings; review that separately. The current
desktop GitHub connection is the owner account, so provisioning a separate agent
identity remains an owner action. Never impersonate an independent reviewer.

CODEOWNERS currently names only the owner. Owner-authored PRs cannot satisfy
an owner review of their own work. Use a distinct non-admin agent author and
the owner reviewer, or obtain owner authorization to change the review model.
Do not silently weaken protection to solve this bootstrap/access issue.

Stop and ask the owner if a change needs new authority, broader privileges,
public database access, destructive schema/data work, payment initiation, or a
secret/security exception. Record exceptions and evidence without secret values,
real emails/PDF contents, raw SMS, tokens or customer financial records.
