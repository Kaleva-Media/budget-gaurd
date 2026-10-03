package app.budgetguard.android.dashboard

import kotlin.math.abs

/**
 * A private, deterministic first-line investigator. It works only with the
 * normalized dashboard data already loaded on the device and never sends a
 * prompt or financial record to a model provider.
 */
object LocalBudgetInvestigator {
    fun answer(dashboard: MobileDashboard, question: String): String {
        val prompt = question.trim().lowercase()
        return when {
            prompt.contains("pending") -> pendingAnswer(dashboard)
            prompt.contains("salary") || prompt.contains("income") || prompt.contains("money in") -> incomeAnswer(dashboard)
            prompt.contains("unplanned") || prompt.contains("unexpected") || prompt.contains("largest") -> unplannedAnswer(dashboard)
            prompt.contains("budget") || prompt.contains("plan") || prompt.contains("over") -> planAnswer(dashboard)
            else -> negativePositionAnswer(dashboard)
        }
    }

    private fun negativePositionAnswer(dashboard: MobileDashboard): String {
        val safe = dashboard.summariseSafeToSpend()
        val explanation = buildString {
            append("I checked ${dashboard.month}. ")
            append("Your included SMS balances total ${formatZar(safe.bCents)}. ")
            append("BudgetGuard is holding back ${formatZar(safe.rCents)} for unpaid plan items")
            if (safe.pCents > 0) append(" and ${formatZar(safe.pCents)} for pending payments")
            append(", leaving Safe to spend at ${formatZar(safe.safeToSpendCents)}.")

            if (safe.safeToSpendCents < 0) {
                append(" The shortfall is ${formatZar(abs(safe.safeToSpendCents))}: known commitments are larger than the cash currently visible in the included accounts.")
            } else {
                append(" This position is not negative right now, but it can still differ from Plan leftover because planned income is a forecast, not cash.")
            }
        }
        val causes = causeLines(dashboard)
        return if (causes.isEmpty()) explanation else "$explanation\n\nWhat stands out:\n${causes.joinToString("\n") { "• $it" }}"
    }

    private fun pendingAnswer(dashboard: MobileDashboard): String {
        val pending = dashboard.transactions
            .filter { it.status == "pending" && it.amountCents < 0 && it.kind !in setOf("transfer", "reversal") }
            .sortedBy { it.amountCents }
        if (pending.isEmpty()) return "I found no pending outgoing payments in ${dashboard.month}."
        val total = pending.sumOf { abs(it.amountCents) }
        return buildList {
            add("I found ${pending.size} pending ${if (pending.size == 1) "payment" else "payments"} totalling ${formatZar(total)}.")
            pending.take(5).forEach { add("• ${it.merchant}: ${formatZar(abs(it.amountCents))} on ${formatTransactionDate(it.occurredOn)}") }
            add("Pending payments reduce Safe to spend until they post, reverse, or are released.")
        }.joinToString("\n")
    }

    private fun incomeAnswer(dashboard: MobileDashboard): String {
        val income = dashboard.transactions
            .filter { it.status == "posted" && it.amountCents > 0 && it.kind != "reversal" }
            .sortedByDescending { it.amountCents }
        val planned = dashboard.plannedItems.filter { it.direction == "income" }
        val received = income.sumOf { it.amountCents }
        return buildString {
            append("I found ${formatZar(received)} of posted income during ${dashboard.month}")
            append(" across ${income.size} ${if (income.size == 1) "transaction" else "transactions"}.")
            if (planned.isNotEmpty()) {
                append(" The plan expects ${formatZar(planned.sumOf { it.plannedCents })} of income.")
            }
            if (income.isEmpty()) {
                append(" If salary arrived outside this cycle, change the workspace cycle day so it is loaded with the intended plan.")
            } else {
                append("\n\nLargest income entries:\n")
                append(income.take(5).joinToString("\n") { "• ${it.merchant}: ${formatZar(it.amountCents)} on ${formatTransactionDate(it.occurredOn)}" })
            }
        }
    }

    private fun unplannedAnswer(dashboard: MobileDashboard): String {
        val unplanned = unplannedTransactions(dashboard)
        if (unplanned.isEmpty()) return "I found no posted or pending outgoing payments without a plan match in ${dashboard.month}."
        val total = unplanned.sumOf { abs(it.amountCents) }
        return buildList {
            add("I found ${unplanned.size} outgoing ${if (unplanned.size == 1) "payment" else "payments"} without a plan match, totalling ${formatZar(total)}.")
            unplanned.take(5).forEach { tx ->
                val category = dashboard.categories.firstOrNull { it.id == tx.categoryId }?.name
                val classification = category ?: "uncategorised"
                add("• ${tx.merchant}: ${formatZar(abs(tx.amountCents))} · $classification · ${formatTransactionDate(tx.occurredOn)}")
            }
            add("A category helps flexible-budget reporting, but only a plan match explains which expected item the payment settled.")
        }.joinToString("\n")
    }

    private fun planAnswer(dashboard: MobileDashboard): String {
        val overPlan = dashboard.plannedItems
            .filter { it.direction == "expense" && it.actualCents > it.plannedCents }
            .sortedByDescending { it.actualCents - it.plannedCents }
        val overBudget = dashboard.budgets
            .filter { it.remainingCents < 0 }
            .sortedBy { it.remainingCents }
        if (overPlan.isEmpty() && overBudget.isEmpty()) {
            return "I found no expense plan lines or flexible budgets over their limits in ${dashboard.month}. Unplanned or pending payments may still explain a low Safe to spend figure."
        }
        return buildList {
            add("Here are the plan variances that stand out:")
            overPlan.take(5).forEach { item ->
                add("• ${item.name} is ${formatZar(item.actualCents - item.plannedCents)} over its ${formatZar(item.plannedCents)} plan.")
            }
            overBudget.take(5).forEach { budget ->
                val name = dashboard.categories.firstOrNull { it.id == budget.categoryId }?.name ?: "A flexible budget"
                add("• $name is ${formatZar(abs(budget.remainingCents))} over its limit.")
            }
        }.joinToString("\n")
    }

    private fun causeLines(dashboard: MobileDashboard): List<String> = buildList {
        val unplanned = unplannedTransactions(dashboard)
        if (unplanned.isNotEmpty()) {
            add("${formatZar(unplanned.sumOf { abs(it.amountCents) })} left through ${unplanned.size} payments with no plan match; the largest was ${unplanned.first().merchant} at ${formatZar(abs(unplanned.first().amountCents))}.")
        }
        val overPlan = dashboard.plannedItems
            .filter { it.direction == "expense" && it.actualCents > it.plannedCents }
            .sumOf { it.actualCents - it.plannedCents }
        if (overPlan > 0) add("Matched expenses exceeded their plan by ${formatZar(overPlan)}.")
        val uncategorised = dashboard.transactions.count {
            it.amountCents < 0 && it.status in setOf("posted", "pending") && it.categoryId == null && it.kind !in setOf("transfer", "reversal")
        }
        if (uncategorised > 0) add("$uncategorised outgoing ${if (uncategorised == 1) "payment still needs" else "payments still need"} review, so the category picture is incomplete.")
        if (dashboard.transactions.size >= 100) add("Only the latest 100 transactions are loaded, so this is a partial investigation.")
    }

    private fun unplannedTransactions(dashboard: MobileDashboard): List<Transaction> = dashboard.transactions
        .filter {
            it.amountCents < 0 &&
                it.status in setOf("posted", "pending") &&
                it.kind !in setOf("transfer", "reversal") &&
                it.plannedItemIds.isEmpty()
        }
        .sortedBy { it.amountCents }
}
