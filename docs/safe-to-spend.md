# Safe-to-Spend calculation

Safe-to-Spend (STS) answers "How much can I spend today without breaking my plan?"

## Formula

```
STS = B − C
```

Where:
- **B** = Available balance (sum of SMS balances for accounts marked `includeInSafeToSpend`)
- **C** = Total commitments = R + P
- **R** = Remaining planned outflows (planned amount minus matched posted/pending)
- **P** = All open pending outflows on STS accounts (excludes transfers and reversals)

## Critical invariants

### 1. Split-payment matching must not inflate commitments

When a single transaction is matched to multiple planned items ("split payment"), the transaction amount is divided equally across all linked items. This prevents inflating C by counting the same transaction multiple times.

**Example:**
- Transaction: R120 matched to 2 planned items
- **Correct**: Each item receives R60 of matched credit
- **Wrong (old bug)**: Each item receives R120, inflating matched total to R240

**Implementation:** When processing `tx.plannedItemIds`, divide `tx.amountCents` by `plannedItemIds.length` before crediting each item.

### 2. All open pendings must be included, not just a page

The `pCents` component sums ALL pending outflows. Android's ~100-transaction page will silently drop older open pendings and overstate STS.

**Problem:**
- Android provides `summariseSafeToSpend` with only the latest ~100 transactions
- If a user has 120 open pending transactions, 20 are missing from the calculation
- Missing pendings → understated P → overstated STS → user overspends

**Solutions:**

#### Option A: Server-side aggregate (recommended)
Create a dedicated pending-outflow aggregate that Android can query:

```sql
-- RPC or materialized view
SELECT SUM(ABS(amount_cents)) AS pending_outflows_cents
FROM transactions
WHERE user_id = $1
  AND entity_id = $2
  AND account_id = ANY($3)  -- STS accounts only
  AND status = 'pending'
  AND amount_cents < 0
  AND kind NOT IN ('transfer', 'reversal')
```

Android calls this separately and passes the result to the STS calculation.

#### Option B: Domain helper with complete dataset
Use `aggregatePendingOutflows(transactions, stsAccountIds)` with ALL open pendings:

```typescript
import { aggregatePendingOutflows } from "@budgetguard/domain";

// Query ALL open pendings (no pagination)
const allPendings = await db.transactions.findMany({
  where: {
    userId,
    entityId,
    status: "pending",
    amountCents: { lt: 0 },
    kind: { notIn: ["transfer", "reversal"] },
  },
});

const pCents = aggregatePendingOutflows(allPendings, stsAccountIds);
```

#### Option C: Explicit Android contract (minimum viable)
Document that Android MUST query all open pendings before calling `summariseSafeToSpend`. Never pass only a pagination window.

```kotlin
// Correct: query all open pendings
val pendings = db.transactionDao().getAllPendingOutflows(entityId, stsAccountIds)

// Wrong: page of recent transactions (may drop old pendings)
val recentTxs = db.transactionDao().getRecentTransactions(limit = 100)
```

### 3. Matched pending moves R→P with C unchanged

When a pending transaction is matched to a planned item:
- **Before match:** R includes the full planned amount, P = 0 for this item, C = R
- **After match:** R reduced by matched amount, P increased by matched amount, C = R + P unchanged
- **STS unchanged:** The pending was already reserved; matching it just moves the reservation from "planned" to "actual pending"

This is the "Neo dedup" invariant: a matched pending doesn't create a new commitment, it fulfills an existing one.

## Test coverage

Golden tests in `packages/domain/test/safe-to-spend.test.ts` prove:
1. Split payments divide the transaction amount across multiple items without inflating C
2. STS with 100 vs 120 pending transactions produces different (correct) results
3. Matched pending moves R→P with C and STS unchanged

## Implementation checklist

- [x] TypeScript domain: equal-split allocation for matched transactions
- [x] TypeScript domain: documented requirement for complete pending set
- [x] TypeScript domain: `aggregatePendingOutflows` helper with SQL example
- [ ] Android: server-side pending aggregate query (RPC or direct SQL)
- [ ] Android: update STS calculation to use complete pending set
- [ ] Android: test STS accuracy with >100 open pendings
- [ ] Supabase: RPC or view for pending-outflow aggregate (optional, depends on chosen solution)

## References

- Architecture: `docs/architecture.md` § Transaction semantics
- Domain implementation: `packages/domain/src/index.ts` (`summariseSafeToSpend`, `aggregatePendingOutflows`)
- Test suite: `packages/domain/test/safe-to-spend.test.ts`
