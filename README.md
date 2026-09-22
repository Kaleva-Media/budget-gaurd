# BudgetGuard

Agent and deployment rules: read `AGENTS.md` and
[`docs/deployment-policy.md`](docs/deployment-policy.md). Production web/function
releases require latest-commit CI and independent PR approval, a restore-tested
database backup before migrations, and retained releases for code rollback.

BudgetGuard turns transaction notifications into useful, near-real-time budget guardrails. Its primary experience is an offline-first Android dashboard with on-device SMS collection, paired with a React web companion and a Supabase data layer.

For agent onboarding, start with [AGENTS.md](AGENTS.md) and
[the current handoff](docs/handoff.md). The handoff distinguishes implemented
features from verified production behaviour and records unfinished work.

## Product principles

- **Present-tense budgeting:** show what is safe to spend now, not only what was planned at the start of the month.
- **Plan cash flow before spending:** capture expected income, known expenses, savings, and debt payments for each month.
- **Give accounts explicit jobs:** route each planned item through an operational, savings, liability, or rewards account.
- **Keep each entity honest:** Personal and company workspaces have separate accounts, plans, periods, balances, and activity.
- **Raw messages stay private:** Android parses SMS on-device and persists only normalized transaction fields plus a SHA-256 deduplication fingerprint.
- **Pending is not posted:** card reservations affect committed funds without becoming settled spend.
- **Transfers are not expenses:** internal and external transfers remain visible but do not distort budget consumption until categorized otherwise.
- **The amount sign wins:** returned debit orders are reversals, not income.

## Structure

```text
apps/web        React + Vite dashboard, run with Bun
apps/android    Mobile-first native Kotlin dashboard and SMS collector
  parser        Pure Kotlin bank-SMS parsers and regression tests
apps/email-ingress Cloudflare Email Worker for private PDF invoice aliases
packages/domain Shared TypeScript budget calculations and contracts
supabase        Schema, RLS checks, and signed PDF invoice ingestion function
docs            Architecture decisions and delivery notes
```

## Web development

Requirements: Bun 1.2.20 or newer.

```bash
bun install
bun run dev
```

The dashboard uses sanitized demo data until both variables are configured:

```bash
cp .env.example .env.local
```

Add the Supabase project URL and **publishable key** to `.env.local`. Never place a secret or `service_role` key in either client.

Run the web checks:

```bash
bun run test
bun run check
bun run build
```

## Supabase

Requirements: Supabase CLI 2.95.4+ and a running Docker-compatible container engine.

```bash
supabase start
supabase db reset
supabase test db
```

The migrations create profiles, entities, devices, an account map, monthly periods, planned income and expense items, normalized transactions, supplier profiles, invoice review records, and match records that reconcile the plan with reality. Each profile receives an unguessable private address at `inbox.budget.cloudcomms.co.za`. Invoice PDFs live in a private Storage bucket; the mobile client sees them only through the owner's RLS session. Existing data is backfilled into a default Personal entity. The `budget_progress` and `planned_item_progress` views are security-invoker views and include entity isolation. Every exposed table has RLS enabled, explicit authenticated grants, ownership policies, and indexed ownership columns.

The web app has four working surfaces:

- **Overview:** projected surplus, expected income, known commitments, operational account lanes, and transaction review.
- **Monthly plan:** a two-sided income/expense ledger with account assignment and flexible category envelopes.
- **Transactions:** searchable SMS-derived activity, planned-payment labels, and a Quick Sort workbench for bucketing unplanned payments by drag-and-drop or tap. A reversible category pill moves a transaction back out of its bucket and returns it to the tray.
- **Accounts:** four operational accounts separated from long-term savings, home loans, and rewards.

## Android

Open `apps/android` in Android Studio and let it install Android SDK 36. The project targets Android 16, uses minimum SDK 26, and includes a reproducible Gradle wrapper.

The Android app is BudgetGuard's primary daily experience. It includes email authentication and a three-step onboarding flow for profile and account setup, a present-tense dashboard, transaction review and category assignment, editable account purposes and SMS mappings, and system-inset-safe navigation. Users can create and rename entities such as Personal, Kaleva Media, and MTG, switch the entire dashboard between them, and assign or move accounts from the account editor. Each entity has independent periods, carry-over, plans, balances, budgets, and activity. A period selector presents planning boundaries as ranges such as “September – October 2026”; creating the next period carries forward that entity's previous projected surplus and copies its recurring plan items and category limits for a useful forecast. Expected income and expenses can be added, edited, assigned to accounts, repeated monthly, moved between existing periods or entities, or removed directly from the period-plan card. Cross-entity moves clear the old account and any personal/business category that does not apply at the destination; entries with matched payments stay in their original period. Expenses can also be classified as fixed, variable, savings, or debt payments and assigned to an entity-appropriate category: Personal uses the household taxonomy while company and other entities use business expense categories. Its side navigation provides entity switching, Home, Activity, Invoice inbox, Accounts, Profile & sync, and sign-out access; the profile surface shows SMS permission, collector health, pending queue count, and manual sync.

The Invoice inbox surface shows the user's private forwarding address and a review queue. Forwarded PDF invoices are parsed for supplier identity, invoice and due dates, outstanding amount, payment reference, and supplier bank details. Nothing changes the budget until the user confirms the supplier, selects an entity/category/account, and chooses how much to plan in the active period. Approval creates or updates the supplier profile and a one-off planned expense. Extracted bank accounts remain explicitly unverified and are never used to initiate payment.

Account setup includes an explicit **Find in messages** action. The user chooses Absa or FNB, sees a prominent disclosure, grants `READ_SMS`, and selects which locally discovered account identifiers belong to the active entity. The query is bank-filtered and capped, raw bodies are discarded as they are inspected, and only selected normalized account metadata is synced. `RECEIVE_SMS` continues to power automatic detection for new messages independently. Google Play distribution requires an approved SMS-permissions declaration for the SMS-based money-management use case.

Discovered accounts are reviewed in a branded, touch-friendly selection sheet rather than a system checklist. Each card separates the account identity, latest SMS-derived balance, message count, and current entity assignment; tapping anywhere on a card toggles it, and the fixed action area confirms how many accounts will be assigned.

Configure a local build in `apps/android/local.properties` (or use environment variables with the same names):

```properties
sdk.dir=/path/to/Android/sdk
BUDGET_GUARD_SUPABASE_URL=https://your-project.supabase.co
BUDGET_GUARD_SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_key
```

Only use a publishable key in the APK. Never use a secret or `service_role` key. Install the APK, sign in or create an account, complete onboarding, and allow SMS access. Account identifiers from SMS—such as Absa `CHEQ1607` or FNB `FNB-914000`—can be configured from Accounts along with each account's entity, institution, type, usual purpose, display mask, and safe-to-spend treatment. Automatic detection remains enabled: when a supported transaction references an unknown account, a conservative institution-specific account entry is created in the default Personal entity for the user to review and, if needed, reassign.

The parser suite does not need an emulator:

```bash
cd apps/android
./gradlew :parser:test
```

The collector receives multipart SMS broadcasts, parses supported Absa formats and a conservative set of FNB inContact formats, deduplicates messages locally with Room, and schedules an authenticated WorkManager upload whenever a new transaction arrives. FNB parsing requires the bank prefix plus an amount, visible account suffix, transaction date, and available balance; the known `Current a/c..` card-reservation wording is covered. Non-transactional approval/login messages and prepaid-electricity token receipts are rejected. Electricity receipts have no source account or available balance and may accompany the underlying financial transaction, so excluding them prevents leaking meter/token details and avoids double-counting spend. A six-hour network-constrained sweep catches interrupted work. Uploads are idempotent, successful rows are marked locally, unknown account keys are auto-created without disabling detection, and each account's normalized balance is refreshed from its latest uploaded SMS balance. The original SMS body is never stored. There is no notification permission or always-running foreground service.

Build and test the installable debug APK:

```bash
cd apps/android
./gradlew :parser:test :app:assembleDebug
```

The output is `apps/android/app/build/outputs/apk/debug/app-debug.apk`.

## Invoice email development

The inbound email boundary is split in two:

1. `apps/email-ingress` receives mail for `*@inbox.budget.cloudcomms.co.za`, accepts bounded PDF attachments, and signs each upload with a P-256 private key held only by Cloudflare.
2. `supabase/functions/invoice-ingest` verifies the signature and timestamp, extracts text and invoice fields, stores the original PDF privately, and creates an RLS-protected review record.

Run the isolated checks with Node 22+ and Deno 2:

```bash
bun run test:email
deno check --config supabase/functions/invoice-ingest/deno.json supabase/functions/invoice-ingest/index.ts
deno test supabase/functions/invoice-ingest/*_test.ts
```

Cloudflare secrets `INVOICE_INGRESS_PRIVATE_JWK` and `SUPABASE_ANON_KEY` are deployment values and must not be committed. The private JWK is stored as an encrypted Worker secret and imported as a non-extractable P-256 key at runtime. The Edge Function receives only the matching `INVOICE_INGRESS_PUBLIC_JWK`; it cannot forge uploads.

## Delivery sequence

1. Create and link the Supabase project; apply and verify the migration.
2. Match card reservations to settlements and reversals, then match actual transactions to planned items.
3. Add learned merchant/category and account-routing rules with user-editable aliases.
4. Add Absa statement CSV reconciliation or an approved bank-feed integration.
5. Add daily safe-to-spend notifications only after the collector is proven reliable.

See [architecture.md](docs/architecture.md) for the trust boundaries and data flow.

## Production

The production web app and self-hosted Supabase API share
`https://budget.cloudcomms.co.za`. Deployment details and server-side trust
boundaries are documented in [deploy/kaleva/README.md](deploy/kaleva/README.md).
