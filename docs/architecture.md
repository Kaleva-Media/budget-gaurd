# Architecture

## System boundary

```text
Absa / FNB SMS
   │
   ▼
Android receiver → pure parser → local Room queue → WorkManager sync
      │                 │                    │
      │                 │                    └── retries safely when offline
      │                 └── raw SMS discarded
      │                                      │
      └── primary dashboard ◀──── authenticated Supabase Postgres + RLS
                                                   │
                                                   ▼
                                          React web companion

Forwarded invoice email
   │
   ▼
Cloudflare Email Worker → PDF-only boundary → signed Edge Function
                                                    │
                                                    ├── private Storage PDF
                                                    └── RLS invoice review queue
                                                               │
                                                               ▼
                                                  supplier + planned expense
                                                  only after user approval
```

The Android app is both the ingestion authority for SMS and the primary daily budgeting interface. Its authenticated dashboard reads the same RLS-protected entities, accounts, categories, budgets, planned-item progress, debts, debt preferences, and normalized transactions as the web companion. Onboarding captures profile details, accounts, their institutions and normal purpose, and SMS identifiers without disabling automatic account discovery. A separate user-triggered history search asks for a bank, presents a prominent disclosure, and lets the user choose which locally discovered account identifiers belong to the active entity. Users can create and rename entities, switch the dashboard's complete budgeting context, and move accounts between entities. Transaction categories and complete account metadata can be edited natively. Flexible category limits can also be created, changed, and removed natively for the selected entity and period; mutations use the user's authenticated session and the existing owner-scoped `budgets` policies. Expected income and typed expenses can be created and maintained from the mobile period plan, including account/category routing, due days, recurrence, and moves between existing periods or entities. A move is atomic and owner-scoped. It retains routing within the same entity, clears the account on a cross-entity move, clears a category when crossing the personal/business taxonomy boundary, and refuses to move a plan line with matched payments. A period selector can create future monthly boundaries; recurring items and category limits copy forward within the selected entity, and each period's projected surplus becomes that entity's next carry-over. A system-inset-aware application shell keeps content clear of status and navigation bars, while a side navigation drawer exposes entity switching, the main surfaces, profile, sync status, and sign-out. Room remains the durable offline hand-off for newly captured SMS transactions, and network-constrained WorkManager jobs upload them without keeping a foreground service alive. Supabase is the normalized cross-device system of record. The web application remains a secondary large-screen planning and review surface; it never receives SMS permission or raw messages.

### Debt planning

`debts` and `debt_preferences` are owner- and entity-scoped, RLS-protected records. Money is stored in signed integer cents and annual interest in integer basis points. Debt records are liabilities, not bank-account balances or planned payments; onboarding or editing a debt does not create a transaction, alter safe-to-spend, or contact a credit provider.

The pure debt planner is implemented in both the Kotlin mobile domain and the TypeScript domain package. Monthly capacity is selected-period planned income minus living expenses. For a category that has both planned expenses and a flexible limit, the planner uses the larger value instead of double-counting both. Debt-payment plan lines are excluded from living expenses and checked against entered minimum payments. Carry-over is one-time plan value and is not repeated in multi-month projections.

The simulator applies monthly interest, pays every entered minimum, then directs the remaining fixed monthly capacity according to the chosen order. Freed minimums therefore roll into subsequent debts. Existing planned debt-payment lines are compared with the estimate and can produce a warning, but only entered contractual minimums determine basic affordability. The simulator stops at 600 months and treats failure to amortize, unaffordable minimums, or arrears as a professional-support case. For a personal entity this route is registered debt counselling or a debt-management plan; for a business entity the UI instead calls for qualified restructuring advice. A consolidation offer is never inferred: users must enter its APR, term, and fees, and it is recommended only when the calculated payment is affordable and estimated total cost is at least 5% lower (and at least R100 lower) than avalanche. These projections are educational estimates; lender statements and formal advice remain authoritative.

The receiver performs only bounded work: join multipart SMS, parse, insert the normalized record, and enqueue a unique one-time sync. Upload work refreshes the persisted user session, registers the device, resolves or auto-creates the SMS account key, performs an idempotent insert, and refreshes the account from the most recent SMS-derived available balance. It marks each local row synced only after Supabase accepts both operations. A periodic sweep recovers work after process death or interrupted connectivity, and an in-process mutex prevents the immediate and periodic jobs from rotating the same session concurrently.

The invoice path is deliberately separate from SMS collection. Every profile has one active, unguessable alias under `inbox.budget.cloudcomms.co.za`. The Cloudflare Email Worker rejects unknown alias shapes, oversized messages, non-PDF messages, and excessive attachments. It does not retain raw MIME. Each accepted PDF is hashed and uploaded with a five-minute P-256 signature; the private signing key remains at Cloudflare and the Edge Function stores only the public verification key. The Edge Function deduplicates by user and document hash, extracts auditable fields, and stores the original document in a private per-user Storage path. Scanned PDFs are marked for manual review rather than guessed through low-confidence OCR.

An invoice remains `needs_review` until the user confirms it. Approval is one database operation: validate entity/period/account ownership, upsert the supplier, retain extracted bank details as unverified, create a one-off planned expense for the amount chosen this month, and link that plan back to the invoice. This is bookkeeping, not payment initiation. A future payment feature must add independent beneficiary verification and explicit authorization rather than trusting invoice contents.

## Transaction semantics

Amounts are signed integer cents. `occurred_on` is the bank's local calendar date; `occurred_at` remains optional because supported messages do not consistently include a transaction time:

- negative: money leaving the account;
- positive: money entering the account;
- pending card reservation: negative amount with `pending` status;
- returned/unpaid debit: positive amount with `reversed` status and `reversal` kind.

`spent_cents` includes posted, categorized debits and excludes transfers and reversals. `committed_cents` includes pending categorized debits. Safe-to-spend calculations subtract both.

The source fingerprint is SHA-256 over a whitespace-normalized, lower-cased message. It is used only for idempotency. The database unique constraint on `(user_id, source, source_fingerprint)` makes retries safe across devices and network failures.

## Monthly planning model

An `entity` is the top-level budgeting workspace. Every account belongs to exactly one entity, and budget periods, category budgets, planned items, and transactions carry the same entity identity. Categories remain owner-specific but are divided into personal and business taxonomies. Personal entities use household categories; company and other entities use business expense categories, without merging financial totals. Existing records migrate to the default Personal entity. The unique period boundary is `(user, entity, starts_on)`, allowing Personal, Kaleva Media, and MTG to each have a September–October 2026 plan.

SMS resolution remains account-first: an existing bank identifier inherits its account's entity. A genuinely unknown identifier creates a review-needed account in the default Personal entity. Moving that account cascades its transaction history to the destination entity; old plan lines are unassigned from the moved account because those plans remain with their original entity.

A `budget_period` is the monthly planning boundary. It owns the carryover and lifecycle state. Each `planned_item` is a positive amount with an explicit direction:

- income is money expected to arrive;
- fixed and variable expenses are expected outflows;
- savings is treated as a commitment, not leftover money;
- debt payments are commitments and remain distinct from the liability balance.

Planned items can point to the account responsible for receiving or paying them. The account itself has a role—`operational`, `savings`, `liability`, or `rewards`—and a plain-language purpose. This models account routing without forcing a bank account to double as a spending category.

`planned_item_matches` links one or more normalized transactions to a planned item. Progress is derived in the `planned_item_progress` security-invoker view, so partial payments and split transactions do not require a mutable status that can drift out of sync.

`planned_item_payment_confirmations` records an owner's explicit “mark paid” action for an expense. It is a reversible bookkeeping confirmation, not a transaction, account-balance change, or payment instruction. Only owned expense items can be confirmed. A confirmed item is treated as settled in plan grouping and remaining-commitment calculations, while matched transaction progress remains authoritative evidence of actual movement. Calculations use the greater of the confirmed planned amount and transaction-derived progress, so the two signals cannot double-count an expense. If transaction matches already settle an item, removing a manual confirmation does not make it unpaid.

Projected surplus is:

`carryover + planned income - planned expenses`

Flexible category envelopes remain separate. They answer how much discretionary spending is still safe; the monthly plan answers whether known obligations and savings goals are funded.

## Unplanned-payment triage

An outgoing payment enters Quick Sort when it has no planned-item match and no category. Transfers and reversals are deliberately excluded. Assigning a bucket updates only the transaction category and review state—it does not create or alter a planned expense after the fact. This keeps the original monthly intention intact while still making unplanned spending visible by type.

The web interaction supports drag-and-drop with a pointer and a select-card-then-tap-bucket path for touch and keyboard users. Bucketed, unplanned transactions expose a move-out control that clears their category, restores review state, and returns them to the tray. Undo writes the original category and review state back through the same RLS-protected transaction update.

## Trust and privacy

- SMS sender labels are not proof of authenticity and can be spoofed. Parsed records are bookkeeping signals only.
- Raw SMS, OTPs, login links, and fraud messages must never be uploaded.
- `RECEIVE_SMS` handles new-message auto-detection. `READ_SMS` is requested separately only after a user chooses **Find in messages**, selects a bank, and accepts a prominent disclosure.
- History search uses a bank-filtered inbox query with an explicit projection and cap. Each raw body is discarded immediately after local extraction; only user-selected normalized account metadata can leave the device.
- The Android client recognizes only explicit transaction formats beginning with an Absa or FNB contract prefix. FNB messages must also contain an amount, account suffix, date, and available balance.
- Prepaid-electricity fulfilment receipts are not financial ledger events: they lack a source account and available balance, may duplicate a separate purchase notification, and contain meter and token secrets. The parser explicitly discards them without persistence.
- Client applications use Supabase publishable keys and user sessions. The service role key is forbidden in web and mobile builds.
- The Email Worker uses the public anonymous gateway token only to pass the self-hosted function router; P-256 verification is the actual invoice-ingress authentication boundary.
- Supplier bank details extracted from a PDF are untrusted data. They remain marked unverified and cannot initiate a payment.
- Invoice PDFs are limited to 15 MiB, stored in a private bucket, and addressed under the authenticated user's UUID.
- Public tables revoke anonymous access, enable RLS, and authorize rows by `auth.uid()`.
- The collector does not request notification permission or publish transaction notifications.
- Android backups are disabled for the local transaction database in the first release.

## Reconciliation model

SMS is fast but not complete. A later statement importer will reconcile records using account, date window, signed amount, normalized merchant, and reference. Reconciliation can:

- promote a reservation to posted;
- link a returned debit to its original payment;
- mark stale reservations as released;
- identify an SMS transaction missing from the statement;
- import transactions for which no SMS arrived.

Reconciliation updates an existing logical transaction instead of creating budget-impacting duplicates.

## Remaining product decisions

- Mobile password-recovery redirect scheme.
- Whether sync uploads merchant references verbatim or applies additional redaction.
- Production retention, export, and account-deletion policy.
- App distribution: private/internal APK versus Google Play SMS-permission review.
