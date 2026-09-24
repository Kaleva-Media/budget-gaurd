# BudgetGuard handoff

Updated: 2026-09-24. Production source baseline: `2244edb` (`main`).
This document records repository state and historical observations, not a fresh production audit.

## Recent changes

- **2026-09-24 debt coaching deployment**: The `codex/debt-coaching`
  branch turns the existing recommendation into a monthly action plan with detailed
  projections, actual-versus-forecast chart data, payoff events, 25/50/75/100%
  milestones, and comparable avalanche/snowball/hybrid outcomes in both Kotlin and
  TypeScript. Android adds the coaching UI, atomic all-debt monthly check-ins,
  same-month corrections, retained paid-off/archived history, an NCR support route,
  optional generic local reminders, and user-approved 5–30% spending scenarios.
  Spending suggestions use median posted spend from up to three completed months and
  require at least two months plus 75% categorized coverage; they never change a
  limit without opening the existing editor. Migration
  `20260923151253_debt_coaching_and_check_ins.sql` and
  `20260923154242_debt_strategy_selection.sql` add the owner/entity-scoped
  check-in tables, reminder preferences, soft-close state, atomic security-invoker
  RPC, and security-invoker spending view with explicit grants. Local verification
  passed 33 TypeScript domain tests, TypeScript checks, production web build,
  Android parser/app unit tests and lint, debug APK assembly, a fresh full
  migration replay, all 16 pgTAP files (104 tests), local schema lint, email and
  Edge Function checks, and 14 deployment-controller tests. PR
  [#23](https://github.com/Kaleva-Media/budget-gaurd/pull/23) merged as `2244edb`.
  Main CI run
  [35965700883](https://github.com/Kaleva-Media/budget-gaurd/actions/runs/35965700883)
  passed every suite and the required `all-tests` gate. Production deployment
  run [35965888965](https://github.com/Kaleva-Media/budget-gaurd/actions/runs/35965888965)
  restore-tested its backup, applied both pending migrations, activated the exact
  tested revision, and passed controller smoke checks. The public version endpoint
  reports `2244edb`; unauthenticated REST reads of the check-in, check-in balance,
  and spending-history datasets return `401`. The owner-authorized temporary
  review exception was restored immediately after merge; main again requires one
  independent review, including code-owner and last-push approval. Android version
  is `0.6.0` (code 16); the debug APK SHA-256 is
  `b734666739fedfc4428ffe8de2de6d47e1dec15310d492882c12251e3e415073`.
  Installed-device behaviour remains unverified.

- **2026-09-23 debt management deployment**: Android now has a separate
  Debt freedom destination with owner/entity-scoped debt onboarding, balances,
  APRs, minimums, optional remaining terms and due days, secured/arrears flags,
  strategy preferences, payoff ordering, and estimates. The pure Kotlin and
  TypeScript engines recommend avalanche, snowball, a quick-win/avalanche
  hybrid, review of a user-entered consolidation offer, or registered debt
  counselling/debt-management support. Monthly capacity uses planned income,
  living expenses, and flexible limits without repeating carry-over or
  double-counting category plans. Arrears, unaffordable minimums, and failure to
  amortize within 600 months route to professional support; users are never told
  to stop payments. The additive migration creates private `debts` and
  `debt_preferences` tables with authenticated CRUD grants, RLS, owner/entity
  checks, and denial tests. Local verification passed 30 TypeScript domain tests,
  TypeScript checks, the production web build, Android parser/app unit tests,
  Android lint, debug APK assembly, all 15 pgTAP files (83 tests), local schema
  lint with no errors, 14 deployment-controller tests, and deployment policy.
  The debug APK is `BudgetGuard-0.5.4-debt-freedom-debug.apk` with SHA-256
  `d344773b63970f3ad9fb2b33e95ea7d180e5f68b188eb9e07dfdaf5b9955f995`.
  PR [#21](https://github.com/Kaleva-Media/budget-gaurd/pull/21) merged as
  `167a29f`. Main CI run
  [35822243324](https://github.com/Kaleva-Media/budget-gaurd/actions/runs/35822243324)
  passed every suite and the required `all-tests` gate. Production deployment
  run [35822415910](https://github.com/Kaleva-Media/budget-gaurd/actions/runs/35822415910)
  restore-tested its backup, applied the one pending migration, activated the
  exact tested revision, and passed controller smoke checks. The public version
  endpoint reports `167a29f`; unauthenticated REST reads of both new tables
  return `401`. The owner-authorized temporary review-count exception was
  restored immediately after merge; main again requires one independent review,
  including code-owner and last-push approval. `adb` was not available locally,
  so installed-device behaviour remains unverified.

- **2026-09-22 native flexible-budget setup (deployed 2026-09-23)**: Android users can
  now add, edit, change, and remove monthly category limits for the selected
  entity and period. The dashboard shows total flexible money remaining, a
  daily pace, pending spend, each category's used/remaining position, and an
  explicit over-limit state. Category choices are restricted to the active
  personal or business taxonomy already loaded for that entity, and duplicate
  limits are prevented in the editor. Existing owner-scoped `budgets` RLS and
  authenticated grants cover the mutations, so this change requires no schema
  migration. New periods continue copying the prior month's limits. Source
  verification passed Android parser/app unit tests, Android lint, debug APK
  assembly, all 23 TypeScript domain tests, TypeScript checks, and all 14 local
  pgTAP files (69 tests), including create/read/update/delete and cross-owner
  denial for category limits. The source is merged in `167a29f`; the Android APK
  is built but not installed-device verified.
- **2026-09-22 planned daily allowance clarification (deployed 2026-09-23)**: Android now
  shows a future period's projected plan surplus divided by that month's day count
  as the planned daily allowance, instead of relabelling the current account-backed
  Safe-to-spend total. Negative forecasts are labelled as daily shortfalls. Current
  periods retain account-backed Safe to spend, while past periods show their plan
  result rather than today's account balances. Android unit tests, debug compilation,
  APK assembly, all 23 TypeScript domain tests, and TypeScript checks pass. This
  source is merged in `167a29f`; the Android APK is built but not
  installed-device verified.
- **2026-09-22 search/paid deployment**: `7e26a20` is live. Deployment run
  `35749146989` completed successfully after a restore-tested database backup,
  applied one pending migration (`20260922122818_planned_item_payment_confirmations.sql`),
  activated the matching web/function release, and passed the controller smoke
  checks. Public Auth health passed and anonymous REST access to the new table was
  denied. The Android debug APK was built from this main revision and passed parser,
  app unit, and assembly tasks; installed-device behaviour remains unverified.
- **2026-09-22 production deployment**: `66ed569` is live. Deployment run
  `35710368498` completed successfully after a restore-tested database backup,
  applied `20260922043832_planned_item_moves_and_category_scopes.sql`, activated
  the matching web/function release, and passed the controller smoke checks.
- **2026-09-22 repository transfer**: repository ID `1373688513` moved to
  `Kaleva-Media/budget-gaurd`. Main protection, deployment environments, secrets,
  and Actions variables were preserved. The `BudgetGuard Admins` team has repo
  admin permission. The email invitation for `lerato@kalevamedia.com` is still
  pending because the current GitHub CLI token lacks the `admin:org` OAuth scope.
- **D-017 (2026-09-17)**: SHA-pinned all GitHub Actions in `.github/workflows/ci.yml` to satisfy repository policy requiring full commit hashes. PR [#5](https://github.com/Kaleva-Media/budget-gaurd/pull/5) merged to `dev` at `e17a7f2`.

## Search, expense ordering, and paid grouping — 2026-09-22 deployed

The `codex/search-paid-expenses` branch adds plan search across income and expenses on Android and web, orders expenses by name or highest amount, and separates To pay from Paid. An unpaid expense can be manually marked paid in one action; a manual confirmation can be undone, while an expense settled by matched transactions remains paid.

Manual confirmation is stored in the owner-scoped `planned_item_payment_confirmations` table. It does not create a transaction or alter an account balance. The RLS insert policy accepts only an owned expense, and authenticated clients receive only select/insert/delete grants. Safe-to-spend and cash-flow calculations treat the confirmation as settlement without adding it to transaction-derived progress, preventing double-counting.

Source verification passed 23 TypeScript domain tests, TypeScript checks, the production web build, a headless production-preview interaction covering search/order/paid/undo behavior, Android app unit tests and Kotlin compilation, deployment policy checks, all 14 pgTAP files (62 tests), local schema lint, and Supabase security/performance advisors with no issues. The feature migration and web UI are deployed at `7e26a20`; the APK is built but has not yet been device-verified.

## Planned-item moves and category scopes — 2026-09-22 source change

The `codex/deploy-planned-item-moves` branch adds an Android move action for planned
income and expenses. Users can choose any existing period across active entities.
Same-entity moves keep account/category routing; cross-entity moves clear the old
account and clear a category when it crosses between the personal and business
taxonomies. Moves with recorded payment matches are refused so reconciled actuals
cannot silently change month or entity. The database operation is atomic,
authenticated, and ownership-scoped.

Categories now have a `personal` or `business` scope. Existing categories remain
personal, while fourteen business categories are added for existing and new users.
Android selects the taxonomy from the active entity kind; the current web companion
continues to use the default Personal entity and filters to personal categories.
This change is merged and deployed to the web/function/database production stack
at `66ed569`. The Android source and debug build are verified locally, but the new
APK has not been installed or device-verified. The move RPC locks the source
row, remains `security invoker`, and is executable only by `authenticated`; the
business-category trigger is also `security invoker`. The migration checker now
permits only fixed trigger invocation and authenticated-only function grants while
continuing to reject dynamic execution and privileged functions.

The exact combined candidate passed 20 domain tests, TypeScript checks, configured
and demo web builds, both headless browser modes, five email tests, Worker dry-run,
four Deno tests, Android parser/app unit tests, debug APK assembly, 14 deployment
policy/controller tests, and deployment-bundle packaging. A fresh disposable
Supabase stack applied every migration; all 13 pgTAP files passed (55 tests),
including move semantics and ownership isolation, and the local Supabase schema
linter reported no errors. The disposable stack was removed.

## Deployment governance — 2026-09-17

Read `docs/deployment-policy.md` before production work. The guardrails are active
on `main`.

Live GitHub controls: protected main requires strict/up-to-date `all-tests`
bound to GitHub Actions, one independent approving review, stale-review
dismissal, last-push approval, code-owner review and resolved conversations.
Administrators are included; force pushes/deletions are prohibited; squash only.
Production and rollback environments permit only main; rollback requires owner
approval. CI credentials are environment-scoped and host keys pinned.

The root-owned forced-command controller is installed and its private-port/RLS/
invoker-view/private-bucket verification passes. The dedicated deployment key
successfully verified and was refused arbitrary shell execution. BudgetGuard's
database and Studio have no published ports; pooler and Envoy are loopback-only.
The shared host has unrelated public services; no changes were made to those.

A full database dump was successfully restored into a temporary private test
database **before** adopting the five already-applied historical migration
checksums. The first restore attempt correctly blocked adoption because Supabase
postgres cannot set extension restore parameters; the internal admin was then
used for the temporary restore only. Historical application SQL was not replayed.
The baseline evidence and dump checksum are protected in
`/var/lib/budgetguard-deploy/baseline.json`; the private ledger is
`deployment_control.migrations`.

The guardrail workflows are active on `main`. They expand CI
to web/domain, built-browser demo/auth checks, Android parser/app tests, email
tests/checks/Worker dry run, Deno tests/checks, isolated database migration/pgTAP
tests, and controller/migration-policy tests. Automated web/invoice-function
deployment and approved code rollback are active. Retained schema is additive; database
restore is separate owner-authorized recovery. Same-host dumps do not protect
Storage file bytes or provide off-host disaster recovery.

Remaining governance setup: accept Lerato's organization/team invitation after it
is successfully sent. Until then, both desktop GitHub connections authenticate as
the owner and cannot independently approve an owner-authored PR. Main still requires
independent review; any temporary exception must be explicitly authorized and restored.
Cloudflare email-Worker automated publishing still needs a scoped credential;
its CI dry run is not a live deployment or proof of invoice delivery.

Local controller tests (13), domain tests (5), email tests (5), Deno tests (4),
type checks, configured web build, both headless browser modes and Android
parser/app unit tests passed. The Worker dry-run bundle passed without publishing.
Local Supabase tests were initially unavailable because Docker's API returned
500. GitHub CI exposed a retired Android action default (`tools` package), now
corrected to platform-tools. A redundant local reset hit a 502 during restart;
CI now checks a fresh inbound-firewalled stack directly instead. Every suite and
the aggregate `all-tests` passed on application/controller commit `d4c9508`,
including migration-chain and cross-user/cross-entity/private-PDF denial tests.
Evidence: [push CI](https://github.com/Kaleva-Media/budget-gaurd/actions/runs/35160606331)
and [PR CI](https://github.com/Kaleva-Media/budget-gaurd/actions/runs/35160609889).
Any subsequent documentation-only commit must also pass the full latest-commit
gate; see [PR #1](https://github.com/Kaleva-Media/budget-gaurd/pull/1) for that
historical status. The owner-authorized temporary review exception used for the
2026-09-22 merge was restored immediately afterward. Production public auth health
passed and anonymous bank-account API access was denied.

## What the user wants

BudgetGuard replaces spreadsheet-style personal and business budgeting with a mobile-first dashboard. Users can manage Personal, Kaleva Media, MTG, or other entities; assign bank accounts; describe what each account is normally used for; enter income and expenses; and plan upcoming monthly periods. SMS-derived available balances are account-specific. Surplus carries into the next period, currently using projected plan surplus rather than reconciled actual month-end cash.

The user also wants a private forwarding inbox for PDF invoices and statements. Extract supplier identity, dates, outstanding amount, payment reference, and bank details; let the user choose the entity and amount to budget this month; and maintain a supplier profile. Direct payments and automation are future ideas requiring independent beneficiary verification and authorization.

## Implemented versus verified

| Area | Repository implementation | Verification/status |
| --- | --- | --- |
| Mobile dashboard | Safe-to-spend, monthly plan, activity, accounts, safe system insets, side drawer, profile/sync/logout | User reported the app and bank account discovery working during development; not a new device test on 2026-09-17 |
| Entities and planning | Entity switching/account assignment, monthly period selection, projected carryover, recurring plan copies, native income/expense editing | Implemented; no fresh production regression recorded in this handoff |
| SMS ingestion | Absa and conservative FNB parsing, local Room queue, WorkManager uploads, discovery and latest SMS balance updates | Parser regression suite exists; bank discovery previously accepted by user |
| Invoice review | Mobile address copy/review UI, private documents, extraction, supplier profile and planned expense after approval | Implemented; live forwarded-document delivery remains unverified/blocking |
| Web companion | Overview, monthly plan, account lanes, activity and Quick Sort | TypeScript checks passed before initial push |
| Payment initiation | None | Not implemented; never infer permission from an invoice |

Android source build version: `0.6.0`, version code `16`. This does not prove which APK a user currently has installed.

Before the initial push, `bun run test` (5 domain tests), `bun run test:email` (5 email parsing/signing tests), and `bun run check` passed. Android, function/Deno, database RLS, and real-email tests were not re-run as part of that push. The original CI checked domain/web and Android parser tests only; expanded guardrail CI is described above. Installed Android UI and real email delivery remain separate verification.

## Highest priority: finish live invoice delivery

Last live observations were on 2026-09-15; recheck them before changing anything:

1. The user's forwarded PDFs were visible in Cloudflare Email Routing activity but failed delivery to `budgetguard-invoice-ingress` with a Worker exception. The latest examined message was a forwarded Vodacom invoice/statement. No real invoices had appeared in `public.invoices` at that point.
2. Worker logs showed repeated redirects to the same ingestion URL. A temporary HTTP handler with `redirect: "manual"` exposed Cloudflare error `1042` when fetching the BudgetGuard API.
3. Normal external and server-local requests to the public function endpoint returned the expected unauthenticated `401`, without redirects. Origin access logs had no corresponding Worker requests. The failure therefore appeared to be in Cloudflare subrequest routing before the origin.
4. Cloudflare documents `1042` as a same-zone Worker fetch restriction requiring `global_fetch_strictly_public`. That flag is committed in `apps/email-ingress/wrangler.jsonc`, but enabling it in production was **not confirmed**. The dashboard picker did not expose it; authenticated Wrangler deployment is the intended next route.
5. The signing-key implementation was fixed from an unsupported CryptoKey binding to the encrypted string secret `INVOICE_INGRESS_PRIVATE_JWK`, imported as a non-extractable P-256 key. The final private/public pair was rotated and a signed, non-writing probe returned the expected `404 Invoice inbox not found`, validating the signature/gateway path. Do not rotate again without evidence and authorization.
6. Last observed live Worker version: `6667cc98`, with a temporary public HTTP diagnostic handler. Earlier versions included `789442bb` (corrected signing implementation) and `83d5c495` (manual redirect diagnostic). These are historical short IDs; look up full IDs and current deployments before rollback.

Important source/live drift: checked-in `src/index.ts` has only an `email` handler, bounded uploads, structured exception logs, manual redirects, and redirect-status logging. The last observed dashboard deployment included an additional temporary HTTP probe and may lack some source logging. Deploying checked-in source should remove the diagnostic handler, but verify the resulting live version and settings.

Cloudflare reference: [Workers errors](https://developers.cloudflare.com/workers/observability/errors/) and [global fetch compatibility flag](https://developers.cloudflare.com/workers/configuration/compatibility-flags/#global-fetch-strictly-public). Treat the flag as the proposed fix, not a proven resolution until real delivery succeeds.

### Next steps, in order

1. Inspect current Cloudflare deployment, compatibility flags, recent email activity, Worker logs, function logs, and invoice counts. A later operator may already have fixed it.
2. Confirm the deployed endpoint/secrets match the repo and server. Check secret **presence**, not values; retain the matching signing pair.
3. Obtain scoped production authorization, authenticate Wrangler to the correct Cloudflare account, run tests and a dry-run bundle, then deploy the checked-in Worker including both compatibility flags. The GitHub account is not Cloudflare authentication.
4. Verify the active version, settings, and absence of the temporary public probe. Do not leave diagnostic endpoints that cause server calls publicly available.
5. Check email retry activity. If retries have expired, ask the user to forward a test PDF again; do not access or resend unrelated mailbox contents.
6. Confirm the entire chain: Email Routing success → Worker accepted log → function accepted response → private PDF + owner invoice review record → mobile refresh.
7. Review extraction with the user before approval. An email delivery success alone is not proof that the outstanding amount was parsed correctly. A statement with multiple charges may require manual review; OCR is not implemented.
8. Verify duplicate forwarding produces one logical invoice, and approval creates the selected entity's supplier/one-off expense only for the amount the user chose. Check another user's session cannot read the invoice or PDF.
9. Record the live version and exact test results here. Add email/function checks to CI in a separately scoped change.

## Environments and access

Production app/API: `https://budget.cloudcomms.co.za`.

| Component | Last known location |
| --- | --- |
| Host | `kaleva.cloud`, observed SSH target `ubuntu@154.65.102.227` |
| Static releases/current symlink | `/var/www/budget-guard` |
| Self-hosted Supabase | `/home/ubuntu/Databases/supabase-fix/projects/budget-guard` |
| Database container | `budget-guard-db` |
| Functions container | `budget-guard-edge-functions` |
| Nginx vhost | `/etc/nginx/sites-available/budget.cloudcomms.co.za` |
| Loopback gateway | `127.0.0.1:12421` |
| Backup directory | `/home/ubuntu/backups/budget-guard` |
| Cloudflare Worker | `budgetguard-invoice-ingress` |
| Cloudflare account ID | `9c9073a0c3fb94b77351fcba3dcaa2eb` |
| Cloudflare zone ID (`cloudcomms.co.za`) | `52d35231da23e4bfb063f6a412039840` |
| Dedicated email subdomain | `inbox.budget.cloudcomms.co.za` |
| Historical workers.dev URL | `https://budgetguard-invoice-ingress.jjeffasen.workers.dev/` |

Request SSH/Cloudflare access from the project owner when unavailable. Do not invent credentials or extract browser/session tokens. Private per-user inbox aliases and real documents intentionally are not included here.

Configuration sources (values are not committed):

- Web: root `.env.local`, `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY`.
- Android: `apps/android/local.properties` or environment, `BUDGET_GUARD_SUPABASE_URL` and `BUDGET_GUARD_SUPABASE_PUBLISHABLE_KEY`. An installable build fails if either is blank; this protects against the earlier “This build needs a home” screen. Existing installed APKs need rebuilding/reinstalling to change embedded configuration.
- Worker: encrypted secrets `INVOICE_INGRESS_PRIVATE_JWK` and `SUPABASE_ANON_KEY`; non-secret limits/endpoint in `wrangler.jsonc`.
- Function: runtime `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, and matching `INVOICE_INGRESS_PUBLIC_JWK`. Self-hosted runtime/mount configuration must be inspected on the server; it is not fully reproduced in this repo.
- Server: protected Supabase `.env` in the stack directory. Obtain only the settings needed for a task and never print or copy its complete contents into logs or Git.

Git access note: the repository is organization-owned at `Kaleva-Media/budget-gaurd`.
The current GitHub CLI account is `edward-kalevamedia`; verify authentication and
organization scopes on another machine rather than assuming this arrangement exists there.

## Operational checklist

The guardrails workflows replace manual web/function/migration deployment after
reviewed activation. The checklist below is historical operator guidance; use
`docs/deployment-policy.md` and the deployment README for the current guarded
workflow. Worker publishing and destructive recovery still require scoped
operator authorization.

### Preflight and read-only diagnostics

- Read the deployment README; confirm target account, host, stack, active releases, and current migration history.
- Use scoped logs; avoid dumping request bodies, PDFs, raw SMS, complete container environments, or secret-bearing compose output.
- On the server, `docker logs --since 30m budget-guard-edge-functions` can show recent function failures. Review privately and redact sensitive output before sharing.
- A privileged invoice count check (authorized operator only):

```sh
docker exec budget-guard-db psql -U postgres -d postgres -P pager=off -c \
  'select status, extraction_status, count(*) from public.invoices group by status, extraction_status;'
```

Privileged counts do not test RLS. Use distinct authenticated test users for ownership tests.

### Deploy

- Web: configure only the public client URL/key, run the README checks and `bun run build`, stage `apps/web/dist` in a new server release, and atomically switch the existing current symlink only after confirming its actual layout. Preserve the previous release for rollback.
- Android: build with the correct public configuration, run parser/app unit tests, and provide the APK for user installation. Verify safe insets, sign-in, entity/period switching, plan editing, account discovery, and sync on a device. Release signing/distribution is not configured by this handoff.
- Worker: with authorized Cloudflare authentication and Node 22+:

```sh
cd apps/email-ingress
bun run test
bun run check
bunx wrangler deploy --dry-run
bun run deploy
```

Check Wrangler's account/version output and secret bindings before publishing; do not reset or overwrite existing secrets. Earlier attempts accidentally used system Node 16. Use a supported runtime explicitly if the shell resolves an old Node. A Wrangler OAuth login was attempted but not confirmed complete.

- Function: run Deno checks/tests, inspect the existing functions mount and runtime entry point, stage the complete `invoice-ingest` directory, preserve the previous deployed copy, and reload only the required service using the existing stack procedure. Do not assume hosted `supabase functions deploy` targets this self-hosted server.
- Database: make a protected recovery dump first, inspect applied migration history, and apply only pending forward migrations with the existing server procedure. Never replay all migration files blindly, run production `db reset`, or re-run `provision-supabase.sh` on this existing installation.

### Smoke tests and rollback

- Smoke-test sign-in, REST reads for the authenticated owner, entity isolation, and the specific changed workflow. Email changes require a real forwarded PDF and verification of the full review path above.
- Record previous/new web releases, function copies, Worker full version IDs, and applied migration identifiers before changing them.
- Roll back web via the previous release symlink, Worker via its recorded deployment/version, and functions via the preserved copy using the inspected live service procedure. Re-test the affected workflow afterward.
- Database rollback is not “delete a migration”: prefer a forward corrective migration. Restoring a backup can lose newer writes and requires explicit recovery authorization and a coordinated plan.
- Backups currently are same-host database dumps with 14-day retention; off-host recovery and private PDF Storage backup coverage are not established. Do not claim full disaster recovery from a database dump alone.

## Recent completions

- **M2 safe-to-spend** (PR #4, commit `436ae6f`): Canonical `docs/safe-to-spend.md` definition, `summariseSafeToSpend` domain export with UK spelling, 15 golden tests. STS = B − C formula locked; Neo dedup rule implemented (P includes all pending, matched + unmatched); planned income excluded; negative STS allowed. Android must mirror the return fields `{ bCents, rCents, pCents, cCents, safeToSpendCents }`.

## Other unfinished work and decisions

- Pending-card reservation/settlement/reversal reconciliation and matching actual payments to planned items.
- Learned merchant/category routing and user-editable aliases.
- Statement CSV reconciliation or an approved bank feed.
- Scanned-document OCR and more invoice/statement extraction regression fixtures (sanitized only).
- Password recovery redirect scheme and production SMTP verification.
- Financial-data retention/export/account deletion and privacy/redaction policy.
- Private APK distribution versus Google Play SMS-permission approval; release signing and distribution process.
- Daily spending notifications only after reliable collection/sync is demonstrated.
- CI for invoice ingress, Deno extraction/signatures, database RLS, and Android app tests.
- Security and DR posture documented in `docs/risk-register.md`; R4 (DR gaps) remains Open pending off-host backup plan.

Some production README/bootstrap notes predate multi-entity onboarding. Inspect the latest migrations and actual runtime data before relying on account seed counts or legacy mappings. Future agents should keep this document concise and replace historical blockers with verified outcomes as work completes.
