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
        val matches = matchingTransactions(dashboard, prompt)
        return when {
            prompt.isBlank() || prompt == "help" || prompt.contains("what can you") -> helpAnswer(dashboard)
            prompt.contains("pending") -> pendingAnswer(dashboard)
            prompt.contains("salary") || prompt.contains("income") || prompt.contains("money in") -> incomeAnswer(dashboard)
            prompt.contains("unplanned") || prompt.contains("unexpected") || prompt.contains("largest") -> unplannedAnswer(dashboard)
            prompt.contains("budget") || prompt.contains("plan") || prompt.contains("over") -> planAnswer(dashboard)
            prompt.contains("negative") || prompt.contains("safe to spend") || prompt.contains("shortfall") -> negativePositionAnswer(dashboard)
            matches.isNotEmpty() -> matchingTransactionAnswer(question, dashboard, matches)
            prompt.contains("spent") || prompt.contains("spending") || prompt.contains("paid") || prompt.contains("payments") || prompt.contains("transactions") || prompt.contains("what happened") -> spendingAnswer(dashboard)
            prompt.contains("recent") || prompt.contains("latest") || prompt.contains("last transaction") -> recentActivityAnswer(dashboard)
            else -> overviewAnswer(dashboard, question)
        }
    }

    private fun helpAnswer(dashboard: MobileDashboard): String =
        "I can investigate ${dashboard.month} using the transactions already on this phone. Ask about Safe to spend, pending payments, salary or income, unplanned spending, budget overruns, recent activity, or a merchant name."

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

    private fun spendingAnswer(dashboard: MobileDashboard): String {
        val outgoing = dashboard.transactions
            .filter { it.amountCents < 0 && it.status in setOf("posted", "pending") && it.kind !in setOf("transfer", "reversal") }
            .sortedBy { it.amountCents }
        if (outgoing.isEmpty()) return "I found no posted or pending outgoing payments in ${dashboard.month}."
        val posted = outgoing.filter { it.status == "posted" }.sumOf { abs(it.amountCents) }
        val pending = outgoing.filter { it.status == "pending" }.sumOf { abs(it.amountCents) }
        return buildList {
            add("I found ${formatZar(posted)} posted and ${formatZar(pending)} pending outgoing payments in ${dashboard.month}.")
            add("Largest outgoing entries:")
            outgoing.take(5).forEach { add("• ${it.merchant}: ${formatZar(abs(it.amountCents))} · ${it.status} · ${formatTransactionDate(it.occurredOn)}") }
        }.joinToString("\n")
    }

    private fun recentActivityAnswer(dashboard: MobileDashboard): String {
        val recent = dashboard.transactions.sortedWith(compareByDescending<Transaction> { it.occurredOn }.thenByDescending { it.id })
        if (recent.isEmpty()) return "I found no transaction activity in ${dashboard.month}."
        return buildList {
            add("Here is the latest activity loaded for ${dashboard.month}:")
            recent.take(5).forEach { transaction ->
                val direction = if (transaction.amountCents < 0) "out" else "in"
                add("• ${transaction.merchant}: ${formatZar(abs(transaction.amountCents))} $direction · ${transaction.status} · ${formatTransactionDate(transaction.occurredOn)}")
            }
        }.joinToString("\n")
    }

    private fun matchingTransactionAnswer(
        question: String,
        dashboard: MobileDashboard,
        matches: List<Transaction>,
    ): String = buildList {
        val net = matches.sumOf { it.amountCents }
        add("For “${question.trim().take(80)}”, I found ${matches.size} matching ${if (matches.size == 1) "entry" else "entries"} in ${dashboard.month}; their net movement is ${formatZar(net)}.")
        matches.take(5).forEach { transaction ->
            val direction = if (transaction.amountCents < 0) "out" else "in"
            add("• ${transaction.merchant}: ${formatZar(abs(transaction.amountCents))} $direction · ${transaction.status} · ${formatTransactionDate(transaction.occurredOn)}")
        }
    }.joinToString("\n")

    private fun overviewAnswer(dashboard: MobileDashboard, question: String): String {
        val safe = dashboard.summariseSafeToSpend()
        val outgoing = dashboard.transactions.count {
            it.amountCents < 0 && it.status in setOf("posted", "pending") && it.kind !in setOf("transfer", "reversal")
        }
        return buildString {
            append("For “${question.trim().take(80)}”, I found $outgoing outgoing ${if (outgoing == 1) "payment" else "payments"} in ${dashboard.month}. ")
            append("Safe to spend is ${formatZar(safe.safeToSpendCents)} from ${formatZar(safe.bCents)} of included balances after ${formatZar(safe.rCents)} of remaining commitments and ${formatZar(safe.pCents)} pending.")
            append("\n\nTry asking about a merchant name, pending payments, salary, largest unplanned payments, recent activity, or budget overruns for a more specific answer.")
        }
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

    private fun matchingTransactions(dashboard: MobileDashboard, prompt: String): List<Transaction> {
        val ignored = setOf(
            "about", "after", "before", "charge", "charged", "could", "did", "does", "entry", "explain",
            "from", "happened", "have", "merchant", "payment", "please", "show", "tell", "that", "this",
            "transaction", "want", "what", "when", "where", "which", "with", "would", "your",
        )
        val terms = Regex("[a-z0-9]+").findAll(prompt)
            .map { it.value }
            .filter { it.length >= 3 && it !in ignored }
            .toSet()
        if (terms.isEmpty()) return emptyList()
        return dashboard.transactions.filter { transaction ->
            val category = dashboard.categories.firstOrNull { it.id == transaction.categoryId }?.name.orEmpty()
            val searchable = "${transaction.merchant} ${transaction.description} $category".lowercase()
            terms.any(searchable::contains)
        }.sortedWith(compareByDescending<Transaction> { it.occurredOn }.thenByDescending { abs(it.amountCents) })
    }
}
