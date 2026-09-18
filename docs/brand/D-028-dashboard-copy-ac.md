# D-028 — Dashboard copy / label AC (P0)

**Owner:** Priya (product copy + AC) · **Implement:** Neo · **Verify:** Vera  
**Escalate:** Gugu only  
**Status:** Open — no release until Vera PASS  
**Date:** 2026-09-18  
**Source of truth:** `docs/brand/` on `dev` (kit v0 approved)

---

## Problem (Edward / Gugu)

On `dev` dashboard wording:

1. **Safe to spend** and **Plan leftover (not cash)** are not clearly separated.
2. Future-period totals are incorrectly labelled as a **daily allowance**.

---

## Locked strings (do not invent synonyms)

| Role | Exact string |
| --- | --- |
| Primary Home number label | `Safe to spend` |
| Secondary plan figure label | `Plan leftover (not cash)` |
| Help first line | `Use Safe to spend before you buy something.` |
| UI grouping word | `workspace` |

**Forbidden on Home / dashboard (customer-visible):**

- Calling plan leftover "cash", "available", "balance", or "Safe to spend"
- Labelling a **period / future-period total** as "daily allowance", "daily spend", or "per day" unless the value is literally STS ÷ remaining days in period **and** the label is explicit (e.g. `Safe to spend ÷ days left`) — default for D-028: **remove daily-allowance labelling of period totals**
- Same label string for SMS bank-alert balance and Safe to spend
- Gold/accent colour on Safe to spend

---

## Hierarchy (Home)

1. Workspace name (muted)  
2. Period range (muted)  
3. **Safe to spend** — largest  
4. **Plan leftover (not cash)** — smaller, muted (optional but if shown must use exact string)  
5. Footnote / help: not reconciled; bank-alert SMS can be wrong; use Safe to spend before you buy  

Three-way honesty: **SMS bank-alert balance ≠ Safe to spend ≠ Plan leftover**.

---

## Acceptance criteria (Vera-testable)

**AC-D028-1.** On Home (current period), the primary amount's label is exactly `Safe to spend` (character-for-character).

**AC-D028-2.** If a secondary plan figure is shown on Home, its label is exactly `Plan leftover (not cash)` and it is visually secondary to Safe to spend (smaller and/or muted).

**AC-D028-3.** No customer-visible string on Home/dashboard equals or contains `daily allowance` (case-insensitive) for a period total or future-period total.

**AC-D028-4.** Future-period (or non-current period) totals, if shown, use a period-accurate label (e.g. period date range + plan/STS framing) — never "daily allowance".

**AC-D028-5.** Help / info first line on Home is exactly `Use Safe to spend before you buy something.` when that sheet is opened from Home.

**AC-D028-6.** SMS / bank-alert balance, if shown, does not reuse the `Safe to spend` label string.

**AC-D028-7.** Vera PASS required before any release build ships these screens.

---

## Out of scope

- Full visual redesign / brand token sprint (thin tokens #9 may continue in parallel only if it does not delay this P0)
- Dark mode
- Changing STS formula (B−C) — copy/labels only unless a mislabel maps the wrong number to STS

## Done when

PR on `dev` with AC-D028-1…6 satisfied + Vera label confirm PASS logged.
