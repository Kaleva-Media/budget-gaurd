# Safe to spend (Home)

Canonical Home figure for Android-primary BudgetGuard. The domain package (`packages/domain`) and Android native implementation must share this definition (M2 milestone). Record the git commit SHA under test for AC-S4 acceptance criteria.

## Formula

**STS = B − C** (may be **negative** — do **not** clamp at 0).

### B — SMS available (active workspace)

Sum of SMS-derived available balances (`accounts.current_balance_cents`) for accounts in the **active workspace/entity** where:

- `include_in_safe_to_spend = true`
- account is active

### C — Remaining commitments (no double-count)

```
C = R + P
```

**R — Remaining planned outflows**

For each planned **outflow** line (expense + savings commitment + debt payment; **not** income):

```
remaining = max(0, planned_cents − matched_cents)
```

`matched_cents` includes **posted and pending** matches to that line.

**P — Unmatched pending outflows on STS accounts**

Sum of `abs(amount_cents)` for transactions on STS-included accounts in the active entity where:

- `status = pending`
- `amount_cents < 0`
- kind is not `transfer` or `reversal`

### Deduplication rule (locked with Neo)

A pending that is **matched** to a plan line moves rands from **R into P**, but **C stays the same** (counted once). Unmatched pending appears only in **P**.

### Fixture anti double-count (S5 / AC-S5)

Seeded `accounts.current_balance_cents` (B) must **not** already net out pending rows. Inserting a pending transaction must drop STS by exactly that pending amount when unmatched. On settle/post: update matches so the same cents are not removed from STS twice. Document `STS_after_settle` in fixture EXPECTED.md.

## Explicit exclusions

| Not Home STS | Why |
| --- | --- |
| Projected / plan surplus | Forecast, **not cash** |
| Category envelope ÷ days | Discretionary pacing only — **not** AC-S4 |
| Planned income | M5 — must not change Home STS |

## Domain API

```typescript
summariseSafeToSpend(input: SafeToSpendInput): SafeToSpendSummary

interface SafeToSpendInput {
  accounts: Account[];
  plannedItems: PlannedItem[];
  transactions: Transaction[];
}

interface SafeToSpendSummary {
  bCents: number;              // B — total SMS available balances
  rCents: number;              // R — remaining planned outflows
  pCents: number;              // P — unmatched pending outflows
  cCents: number;              // C = R + P (commitments)
  safeToSpendCents: number;    // STS = B − C (may be negative)
}
```

Golden tests with JSON fixtures for Android parity. Cite this document path and commit SHA in Vera runs.
