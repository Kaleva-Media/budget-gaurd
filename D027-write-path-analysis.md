# D-027 Write Path Analysis

## Write path location
**File:** `apps/android/app/src/main/java/app/budgetguard/android/sync/SupabaseCollectorClient.kt`  
**Function:** `confirmPlannedItemMatch` (lines 794-815)

## Implementation
```kotlin
suspend fun confirmPlannedItemMatch(
    plannedItemId: String,
    transactionId: String,
    amountCents: Long,
) {
    require(amountCents > 0) { "A transaction match amount must be positive." }
    val userId = authenticatedUserId()
    client.from("planned_item_matches").upsert(
        NewPlannedItemMatch(
            userId = userId,
            plannedItemId = plannedItemId,
            transactionId = transactionId,
            amountCents = amountCents,  // ✅ Specific allocation per item
        ),
    ) {
        onConflict = "planned_item_id,transaction_id"
        ignoreDuplicates = false
    }
    // ...
}
```

## Callers

### 1. Manual match UI (MainActivity.kt lines 3365-3395)
User picks a transaction, selects one or more planned items, and enters an amount per item.
Each call to `saveManualMatch` → `confirmPlannedItemMatch` passes:
- `plannedItemId`: specific item
- `transactionId`: the transaction
- `amountCents`: user-entered allocation for THIS item

**Split payment flow:**
If user links tx1 (-R90k) to 3 items:
1. User picks item A, enters R30k → `confirmPlannedItemMatch("A", "tx1", 30_000)`
2. User picks item B, enters R30k → `confirmPlannedItemMatch("B", "tx1", 30_000)`
3. User picks item C, enters R30k → `confirmPlannedItemMatch("C", "tx1", 30_000)`

Result: 3 rows in planned_item_matches, each with amount_cents=30000.
Sum = 90k = |transaction.amount_cents|. ✅ Allocated once.

### 2. Quick match UI (MainActivity.kt lines 3031-3046)
System suggests exact matches. Each suggestion has:
- `transaction`
- `plannedItem`
- `amountCents` (from suggestion)

When user confirms, calls `confirmPlannedItemMatch(suggestion.plannedItem.id, suggestion.transaction.id, suggestion.amountCents)`.

Quick matches are 1:1 (one transaction → one item), so amountCents = |tx.amountCents|. No split.

## Verification
✅ **Write path is correct.** Each call to `confirmPlannedItemMatch` writes ONE row with the specific allocation for that (plannedItem, transaction) pair.

✅ **Split payments allocate |amount| once:** If a R90k transaction is linked to 3 items, the user makes 3 separate confirmPlannedItemMatch calls with (30k, 30k, 30k), not 3 calls with (90k, 90k, 90k).

## Web write path
**Status:** None found. Web app (apps/web/src/lib/dashboard.ts) only reads planned_item_matches (line 151-152). No insert/update/upsert operations for matches in web code.

## Conclusion
D-027 write path is correct. Android is the only write path and it correctly allocates amounts per item, not per transaction.
