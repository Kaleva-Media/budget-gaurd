# BudgetGuard handoff

Updated: 2026-09-17. Source baseline: `2fa935c` (M2 safe-to-spend on `cursor/m2-safe-to-spend-36cf`).
This document records repository state and historical observations, not a fresh production audit.

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

Android source build version: `0.5.1`, version code `12`. This does not prove which APK a user currently has installed.

Before the initial push, `bun run test` (5 domain tests), `bun run test:email` (5 email parsing/signing tests), and `bun run check` passed. Android, function/Deno, database RLS, and real-email tests were not re-run as part of that push. CI currently checks domain/web tests, web type checks/build, and Android parser tests; it does **not** cover email ingress, Deno functions, database RLS, or installed Android UI.

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

Git access note: the machine's SSH identity authenticated as `edward-digify` and was denied write access to this repository. The initial push succeeded using the existing GitHub CLI account `edward-kalevamedia` over HTTPS. `origin` retains the supplied SSH fetch URL and has an HTTPS push URL. Verify authentication on another machine rather than assuming this arrangement exists there.

## Operational checklist

This is a safety/checklist runbook, not a claim that production deployment is automated. Exact release naming, function mount paths, migration tracking, and full rollback version IDs must be established from the live system.

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

- **M2 safe-to-spend** (PR #4, commit `2fa935c`): Canonical `docs/safe-to-spend.md` definition, `summariseSafeToSpend` domain export with UK spelling, 15 golden tests. STS = B − C formula locked; Neo dedup rule implemented; planned income excluded; negative STS allowed. Android must mirror the return fields `{ bCents, rCents, pCents, cCents, safeToSpendCents }`.

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

Some production README/bootstrap notes predate multi-entity onboarding. Inspect the latest migrations and actual runtime data before relying on account seed counts or legacy mappings. Future agents should keep this document concise and replace historical blockers with verified outcomes as work completes.
