# PRD: First trustworthy week of use

**Product:** BudgetGuard  
**Owner:** Priya (Product, UX & Localisation)  
**Reviewers:** Gugu (charter fit); Vera (testability)  
**Audience:** Neo (Android), Ada (backend/invoice), Vera (QA)  
**Horizon:** Next 30 days — thinnest trustworthy mobile experience  
**Repo:** https://github.com/edward-kalevamedia/budget-gaurd  
**Date:** 2026-09-17  
**Status:** Charter-approved (Gugu) + **Vera testability Pass** (2026-09-17) — engineering bar locked; Neo/Ada executing M1–M6

---


## Locked product law (Gugu, this slice)

- Three-way Home honesty: **SMS available balance** / **Safe to spend** / **Plan surplus (not cash)**
- **AC-S6**: planned income alone must not inflate Safe to spend (pending Neo/Ada M2 formula confirm)
- UI copy default: **workspace** (code may keep `entity`)
- Naledi discovery remains hypothesis-only until interviews run
- Ping Gugu only for material scope change; Vera testability notes fold without re-approval unless scope shifts




## Canonical Safe to spend (product lock, 2026-09-17)

**Safe to spend = B − C** (±1¢). May be negative; show honestly.

| | Definition |
| --- | --- |
| **B** | Sum of SMS-derived available balances for accounts in the **active workspace** with `include_in_safe_to_spend = true` |
| **C** | Pending/authorised not-yet-posted amounts on those accounts **+** remaining planned outflows for the workspace/period (planned expenses, savings commitments, debt payments still unpaid/unmatched) |

**Out of formula:** category-envelope `committed_cents` / remaining÷days; planned income (AC-S6); projected/plan surplus (secondary not-cash only).

**No double-count:** a pending SMS matched to a planned outflow counts once (implementation rule documented in `docs/safe-to-spend.md`).

## Locked UI strings (Vera-testable)

| Surface | Exact string |
| --- | --- |
| Home primary | `Safe to spend` |
| Home secondary (default) | `Plan leftover (not cash)` |
| Help first sentence | `Use Safe to spend before you buy something.` |
| Invoice bank details | `Unverified` |
| No payment CTA | Absence of any `Pay` action on invoice review |

Allowed secondary synonyms (transition only): `Plan surplus (not cash)`, `What your plan says is left (not cash)`.

## Copy & setup guidance (Naledi research pass, 2026-09-17)

Hypotheses only — interviews not run. Does **not** change locked product law; softens week-1 cognition and setup.

### Three money labels (high cognitive risk)
- Keep three-way honesty mandatory (AC-S*).
- **SMS available balance** — lowest friction (matches Absa/FNB SMS language “available balance”).
- **Safe to spend** — will be heard as cash unless help is unavoidable and plain.
- Avoid leading with jargon **surplus**. Preferred interim UI copy to A/B until verbatim exists: **Plan leftover (not cash)** or **What your plan says is left (not cash)**. Code/docs may still say plan surplus.
- Help sheet **first sentence** must answer: “Which number do I use before I buy something?” → **Safe to spend only.**

### Workspace setup (medium–high drop-off risk)
- Must isolation (AC-E*) unchanged — no reimbursement engine.
- Soften Day-0: allow **Personal-only first glance** at Safe to spend; prompt **Add a business workspace** when the user names a second pot.
- Solo operators with one mixed Absa/FNB account need “assign later / park in Personal” — empty-state + S7 guidance, not forced dual setup.

### Invoice path (material unknown)
- Do **not** make J4 the only way to feel week-1 value. Success of Safe to spend + workspace honesty stands if invoice ingress is Blocked-external.
- L7 WhatsApp/share-target stays Later until habit evidence; Vera fixture path remains essential.

### Language
- en-ZA glossary before zu/xh/af — confirmed.
- Listen in interviews for participant words: “money pots”, “profiles”, “accounts” vs workspace.


## 1. Problem

In the first week, a South African user must trust three things or they will abandon the app:

1. **Safe-to-spend is honest** — the number they act on is clearly cash-aware, not a confusing “plan surplus”.
2. **Entity honesty** — Personal and business money never silently mix; switching context is obvious.
3. **Invoice review is under their control** — extracted invoices never change the budget until they approve.

If any of these fail, more features make the product less trustworthy, not more.

## 2. Goal (30 days)

Ship the **thinnest Android-primary experience** where a user can complete one trustworthy week:

- See a single primary **Safe to spend** figure they can explain in one sentence.
- Keep **Personal** and at least one **business entity** strictly separate.
- Review a forwarded invoice and **approve or reject** before anything hits the plan.

Web remains a companion; no payments; no autopay.

## 3. Non-goals (explicit)

| Out of scope | Why |
| --- | --- |
| Payments / beneficiary pay | Charter boundary; unverified bank details |
| Treating projected surplus as cash | Trust violation |
| Auto-approving invoice extraction | Trust violation |
| Full bank-feed / CSV reconcile | Later; SMS + manual plan is enough for week 1 |
| iOS | Android primary |
| Multi-language UI beyond en-ZA strings audit | Flag gaps only this cycle |
| Daily spend push notifications | Only after collector reliability is proven |

## 4. Personas (thin)

- **Solo operator** — Personal + one small business (e.g. Kaleva Media / MTG-style). Uses Absa or FNB SMS. Irregular income. Needs “can I pay this supplier today?” answered without spreadsheet math.
- **Constraint:** Mid-range Android, intermittent data, en-ZA first; may mix English with local terms (e.g. “airtime”, “debit order”, “available balance”).

## 5. Trust semantics (must not drift)

These definitions are product law for acceptance tests:

| Term | Meaning | Must never mean |
| --- | --- | --- |
| **Available balance (SMS)** | Latest supported SMS-derived available balance for that account | Bank-statement truth; planned income not yet received |
| **Safe to spend** | Money the user may spend now without breaking known commitments in the **active entity & period**, after subtracting committed (pending) amounts | Bank cash alone; projected month-end surplus alone |
| **Projected surplus / Plan surplus** | `carryover + planned income − planned expenses` for the period | Cash in hand; “available balance” |
| **Not reconciled** | SMS + plan signals are bookkeeping aids; they are not a full bank-statement reconcile | “Verified against the bank” |
| **Committed** | Pending categorized card reservations (and similar) | Posted spend |
| **Entity / workspace** | Isolated workspace: accounts, periods, plans, activity | Shared totals across Personal + business |
| **Invoice suggestion** | Extracted fields awaiting review | Approved planned expense |

**On-screen rules (Naledi H2 — top trust killer):**
1. Home must make three ideas distinguishable: **SMS available balance** (per account), **Safe to spend** (primary decision number), **Plan surplus (not cash)** (forecast only).
2. Help sheet must say plainly that balances are **not statement-reconciled**.
3. Plan surplus — if shown — is secondary, labelled **Plan surplus (not cash)**, and never larger/more prominent than Safe to spend on Home.
4. Never use “available”, “balance”, or “safe” for plan surplus without the not-cash qualifier.

## 6. User journeys (week 1)

### J1 — First honest Home glance (Day 0–1)

1. User signs in / completes existing onboarding.
2. Lands on Home for the **active entity**.
3. Sees: entity name, period range (e.g. September – October 2026), **Safe to spend**, short plain-language footnote.
4. Can open a one-tap “What does this mean?” sheet.

### J2 — Entity switch without leakage (Day 1)

1. User opens drawer → switches Personal ↔ business.
2. Entire dashboard (safe-to-spend, plan, activity, accounts, invoices) reflects only that entity.
3. No residual totals, accounts, or invoices from the previous entity remain visible.

### J3 — Capture & classify spend (Days 1–7)

1. Supported Absa/FNB SMS arrives → normalized transaction appears after sync.
2. User can review activity and assign category / match plan where UI already allows.
3. Pending reservations reduce Safe to spend; they are not shown as posted spend.

### J4 — Invoice review before budget change (Days 2–7)

1. User copies private forwarding address from Invoice inbox.
2. Forwards a PDF invoice (when ingress is live; otherwise QA uses fixture/seed path Vera can run).
3. Invoice appears as **Needs review** with extracted fields clearly marked as suggestions.
4. User confirms or edits supplier, entity, category, account, amount for **this** period → Approve.
5. Only then: supplier upsert + one-off planned expense for chosen amount.
6. Reject / dismiss leaves plan unchanged; bank details remain **Unverified — not for payment**.

## 7. Acceptance criteria (Vera-testable)

Each criterion is pass/fail without product debate. Prefer device + seeded data; note blockers (e.g. live email ingress) as **Blocked-external**, not Product fail.

### A. Safe-to-spend clarity

| ID | Criterion |
| --- | --- |
| AC-S1 | Home shows exactly one primary money figure whose visible label is exactly **Safe to spend**. |
| AC-S2 | Secondary figure visible label is exactly one of: (1) `Plan leftover (not cash)` (default to ship); (2) `Plan surplus (not cash)`; (3) `What your plan says is left (not cash)`. Must not use “available”, “balance”, or “safe” unless the same string also contains “not cash”. |
| AC-S3 | Help sheet: **first sentence** is exactly: `Use Safe to spend before you buy something.` Remaining ≤3 short sentences must include: (1) Safe to spend uses SMS available balances and known commitments for this workspace; (2) the **shipped** secondary label (default `Plan leftover (not cash)`) is a forecast and is not cash — help wording must match the label on screen; (3) figures are **not reconciled** to a full bank statement. |
| AC-S4 | Home **Safe to spend** equals the Android Home formula **B − C** (±1¢), where **B** = sum of SMS available balances for accounts in the active workspace and **C** = remaining commitments for that workspace/period including pending. Vera records the git commit/hash under test. `packages/domain` must expose the same B−C function for this AC, or is explicitly out of scope for AC-S4 until it does. Projected surplus / category-envelope helpers (`summariseCashflow`, `summariseBudgets`, Android parallels) must **not** be used as the Home Safe-to-spend source of truth. |
| AC-S5 | Adding a pending card reservation decreases Safe to spend; settling/posting does not double-count. |
| AC-S6 | Increasing planned income alone must **not** increase Safe to spend until reflected in SMS/available balance or an explicitly defined cash input (if none exists, planned income must not inflate Safe to spend). |
| AC-S7 | Account detail (or Home drill-down) can show **SMS available balance** as a distinct label from Safe to spend; the two must not share the same label string. |

### B. Entity honesty

| ID | Criterion |
| --- | --- |
| AC-E1 | Creating entity B and switching to it shows zero accounts/transactions/invoices from entity A unless user moved them. |
| AC-E2 | Moving an account from A→B removes it from A’s Accounts and Safe-to-spend inputs; A’s plan lines that pointed at that account are unassigned (not silently moved). |
| AC-E3 | Period carryover for entity A never includes entity B’s projected surplus. |
| AC-E4 | Invoice approval requires an entity selection; approving into B never creates a planned expense visible in A. |
| AC-E5 | Drawer always shows the active entity name; Home header matches drawer. |

### C. Invoice review

| ID | Criterion |
| --- | --- |
| AC-I1 | Needs-review invoice does not create or alter planned expenses. |
| AC-I2 | Approve with amount X creates exactly one one-off planned expense of X on the selected entity/period/account/category. |
| AC-I3 | Reject/dismiss creates zero planned expenses and leaves supplier bank details unused for any payment action (none exist). |
| AC-I4 | UI labels extracted bank details **Unverified**. No “Pay” CTA exists. |
| AC-I5 | User can edit amount downward/upward before approve; planned expense uses the **confirmed** amount, not only the OCR/extract. |
| AC-I6 | Duplicate of the same PDF for the same user (same content hash) does not create a second logical invoice. **How to run:** preferred = two ingress attempts with the same hash via test harness / authorised operator; live email duplicate-forward is acceptable when Worker delivery is verified, otherwise mark live path **Blocked-external** and pass via harness. |
| AC-I7 | User U2 cannot read U1’s invoice or PDF (RLS). |

### D. Localisation / accessibility (minimum)

| ID | Criterion |
| --- | --- |
| AC-L1 | Finite checklist (all must pass on default en-ZA build): (1) money we format is `R` + space thousands + `.` decimals (or shared `formatZar` en-ZA output), e.g. `R 1 234.56`; (2) shipped default strings contain no US spellings **organize** or **favor**; (3) no other spelling rules in this AC. |
| AC-L2 | Primary actions have content descriptions / accessible names; Safe to spend is reachable by screen reader as a single announcement including currency. |
| AC-L3 | Touch targets for entity switch, approve, reject ≥ 48dp. |

## 8. Ranked backlog (Neo / Ada)

### Must (blocks trustworthy week)

| # | Item | Owner | Notes |
| --- | --- | --- | --- |
| M1 | Home: single primary **Safe to spend** + demoted **Plan leftover (not cash)** + help sheet (first line = use Safe to spend only; include **not reconciled**) | Neo | Three-way honesty; prefer “leftover” over “surplus” until interviews; fix copy that equates leftover/surplus with cash or SMS balance with “verified” |
| M2 | Canonical Home Safe-to-spend = **B − C** (SMS available balances − commitments incl. pending); implement/align Android + `packages/domain`; do **not** treat projected surplus or category-envelope daily burn as AC-S4 done | Neo + Ada | Ada confirmed current helpers ≠ STS; Vera pins commit/hash |
| M3 | Entity switch audit: no cross-entity leakage on Home, Activity, Accounts, Invoices, Plan | Neo | AC-E* |
| M4 | Invoice review: approve/reject with editable amount; unverified bank copy; no Pay CTA | Neo + Ada | Depends on ingress; provide seed/fixture path for Vera if live email blocked |
| M5 | Ensure planned income cannot inflate Safe to spend (AC-S6) | Neo + Ada | Product rule — fix calc or UI if currently wrong |
| M6 | en-ZA currency formatting + string pass on Home, Plan, Invoice review | Neo | See §9 |

### Should (same 30 days if Must lands)

| # | Item | Owner | Notes |
| --- | --- | --- | --- |
| S1 | Empty states that teach trust (“No accounts in this workspace yet”) | Neo | Softens entity-setup pain (Naledi H1/H4) |
| S2 | Pending vs posted visual distinction in Activity | Neo | Plain-language; avoid jargon-only |
| S3 | Invoice inbox: copy-address success feedback + “nothing changes until you approve” banner | Neo | |
| S4 | Accessibility pass on drawer + invoice approve flow | Neo | AC-L2/L3 |
| S5 | Fixture pack for Vera: 2 entities, Absa+FNB sample txs, 1 needs-review invoice | Ada + Vera | Unblocks QA without live Worker |
| S6 | Offline/sync honesty: show Pending upload / last synced without blocking Home Safe to spend | Neo | Thin UX must not assume always-online |
| S7 | Entity setup copy for shared-account / “business pays then I reimburse” cases (guidance, not new ledger) | Neo + Priya | Validate in discovery; don’t build reimbursement engine yet |
| S8 | Plain-language glossary pass: leftover/surplus, pending, posted, workspace — SA English before any translation | Priya + Neo | Naledi: literacy risk |
| S9 | Personal-only first run: see Safe to spend before forced dual-workspace setup; soft “Add a business workspace” prompt | Neo | Must isolation unchanged; reduces Day-0 drop-off |
| S10 | Success metric split: week-1 value = Safe to spend + workspace honesty even if invoice path is Blocked-external | Priya + Vera | J4 not sole value path |

### Later (explicitly not this slice)

| # | Item |
| --- | --- |
| L1 | Payments / beneficiary verification |
| L2 | Statement CSV / bank feed reconcile |
| L3 | Learned merchant routing |
| L4 | Daily spend notifications |
| L5 | Full multilingual UI (zu, xh, af, etc.) — plain en-ZA first |
| L6 | Play Store SMS declaration / public distribution polish |
| L7 | WhatsApp / share-target invoice intake (if interviews confirm email-forward fails beachhead) |

## 9. SA localisation notes (en-ZA first)

### Ship now

- **Locale:** default `en-ZA` for number/currency formatting on Android.
- **Currency:** South African Rand, symbol `R`, decimal `.`, grouping preferred as thin space or locale default — be consistent across Home and Plan.
- **Copy vocabulary (prefer):** available balance, debit order, stop order, paid / unpaid, invoice, statement, safe to spend, plan surplus, entity / workspace (pick one — recommend **workspace** in UI, keep `entity` in code), Personal, business.
- **Banks in scope for copy examples:** Absa, FNB (as in parsers). Do not promise Capitec/Standard Bank in UI yet.
- **Dates:** prefer `d MMM yyyy` (e.g. 17 Sep 2026); period ranges as already used (“September – October 2026”).
- **Tone:** plain, respectful, no US idioms (“checking account” → use **transaction / current account** as appropriate for Absa/FNB wording).

### Gaps to flag (do not block Must)

| Gap | Risk | Recommendation |
| --- | --- | --- |
| isiZulu / isiXhosa / Afrikaans UI | Exclusion for some operators | Research with Naledi; consider bilingual help sheet later |
| Mixed-language SMS / merchant names | Display noise | Show merchant as parsed; don’t “translate” proper nouns |
| Low literacy / small screens | Misread Safe to spend | Keep help sheet ≤3 sentences; large primary figure |
| Data-expensive sync | Abandoned sync | Already offline-first; surface “Pending upload” plainly (Should) |
| WhatsApp-only invoice sharing | Forwarding friction | Later: share-target; for now document email forward path |

### Accessibility

- Don’t rely on colour alone for pending vs posted.
- Announce currency in TalkBack (“1 234 rand and 56 cents” or platform norm).
- Avoid tiny legal footnotes as the only honesty mechanism — honesty belongs in labels.

## 10. Dependencies & risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Live invoice email ingress still broken (handoff) | AC-I* blocked in prod | Ada: seed/fixture path; treat live ingress as infra parallel track |
| Domain vs Android calc drift | Wrong Safe to spend | M2 parity tests |
| Users equate Safe to spend / Plan surplus with bank cash (Naledi H2) | Harmful spend; trust loss | M1 three-way labels + AC-S2/S3/S6/S7 |
| Email-forward invoice habit wrong (WhatsApp/paper) | J4 fails in real life | Keep email path + fixtures for week 1; L7 if evidence says so |
| Entity setup too heavy / shared Absa-FNB accounts | Week-1 abandonment | S1/S7 guidance; discovery validates H1/H4 |
| Always-online or full-bank assumptions | Beachhead fail (Absa/FNB only, offline queue) | S6; parsers-only copy; no fake “all banks” claims |
| Discovery insights arrive mid-cycle | Backlog churn | Naledi → Should/Later first; Must frozen unless trust bug |

## 11. Success metric (qualitative, 30 days)

Edward (or a designated tester) completes J1–J2 (and J3 if SMS fixtures exist) on a debug build and answers **yes** to:

1. “I know which number to use before I buy something: Safe to spend.”
2. “I never saw Personal and business money mixed.”

**If invoice ingress is available or fixtures exist**, also complete J4 and answer:
3. “An invoice did not change my plan until I approved it.”

Week-1 value must stand on (1)+(2) even when J4 is Blocked-external.

Vera signs AC-* as Pass, Fail, or Blocked-external with evidence.


## 11a. Discovery inputs (Naledi, 2026-09-17)

Interviews not run yet (recruit channel blocked). Treated as **hypothesis + product-evidence risk**, not participant quotes.

| Theme | Input | PRD response |
| --- | --- | --- |
| Safe to spend vs cash | Users may read safe-to-spend as bank cash; product uses plan + account-specific SMS, not reconciled statements | Three-way UI honesty; AC-S3/S7; M1 |
| Personal vs business | Core bet: people juggle both; watch reimbursement patterns, shared accounts, accountant-only businesses | Entity isolation Must; S7 guidance only |
| Invoice habits | Unknown; email PDF forward may not match WhatsApp/paper reality; live ingress historically blocked | Fixture path Must/Should; WhatsApp → Later L7 |
| Language | Recruit may use isiZulu/Afrikaans for comfort; no UI-language evidence yet | Plain en-ZA money terms first (S8); translation Later |
| Android / connectivity | Offline SMS queue, no always-on FG service, Absa/FNB only, READ_SMS after disclosure, Play SMS open | S6 sync honesty; no full-bank/always-online claims |

Naledi will ping when first 5–10 interview notes land (verbatim safe-to-spend language, SMS grant conditions, WTP, invoice habits, non-en-ZA needs).

**Research pass 2026-09-17 (no interviews):** three-label cognitive load = high → prefer Plan leftover copy + help-first-sentence; Personal-only first glance; J4 not sole week-1 value; L7 stays Later.

## 12. Open questions (do not block draft review)

1. Exact Safe-to-spend formula today on Android vs `packages/domain` — Neo/Ada confirm in M2.
2. Preferred UI word: **entity** vs **workspace** vs **profile** — Priya default: **workspace** in UI.
3. Whether manual “cash adjustment” exists; if not, AC-S6 stands as written.
4. After interviews: does email-forward survive week 1, or must WhatsApp intake move up from Later?
5. Preferred plain-language replacements for “surplus” / “entity” once Naledi has verbatim phrases.

---

**Next:** Gugu — charter fit. Vera — re-check AC-S3/S7 testability. Naledi — research pass on v2. Neo/Ada — estimate Must after reviews.


## Vera testability log (2026-09-17)

First score: Pass S2/S3/S5–S7, E*, I* (I6 live Blocked-external), L2–L3. **Needs rewrite:** S1, S4, L1 (+ I6 harness, S2 allowlist).  
Rewrites applied; **re-score 2026-09-17: all Pass** (Groups A & D Pass; I6 Pass with harness; live email Blocked-external for execution only). Optional S3: help secondary label must match shipped UI. Ada: M2 = B−C; fixtures for I*.
