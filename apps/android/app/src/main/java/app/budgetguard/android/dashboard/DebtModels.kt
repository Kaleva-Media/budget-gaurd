package app.budgetguard.android.dashboard

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

data class Debt(
    val id: String,
    val name: String,
    val type: String,
    val balanceCents: Long,
    val annualInterestBps: Int,
    val minimumPaymentCents: Long,
    val remainingTermMonths: Int?,
    val dueDay: Int?,
    val secured: Boolean,
    val inArrears: Boolean,
    val isActive: Boolean = true,
    val closedReason: String? = null,
    val closedAt: String? = null,
)

data class DebtPreferences(
    val goal: String = "balanced",
    val preferredStrategy: String = "recommended",
    val consolidationAprBps: Int? = null,
    val consolidationTermMonths: Int? = null,
    val consolidationFeesCents: Long = 0,
    val reminderEnabled: Boolean = false,
    val reminderDay: Int = 28,
)

enum class DebtStrategy {
    AVALANCHE,
    SNOWBALL,
    HYBRID,
    CONSOLIDATION_REVIEW,
    FORMAL_SUPPORT,
}

data class DebtProjection(
    val payoffMonths: Int,
    val totalInterestCents: Long,
    val monthlyPaymentCents: Long,
    val trajectory: List<DebtTrajectoryPoint>,
    val payoffEvents: List<DebtPayoffEvent>,
    val milestones: List<DebtMilestone>,
)

data class DebtTrajectoryPoint(
    val month: Int,
    val remainingBalanceCents: Long,
    val cumulativeInterestCents: Long,
    val targetDebtId: String?,
    val targetDebtName: String?,
)

data class DebtPayoffEvent(val debtId: String, val debtName: String, val month: Int)

data class DebtMilestone(val percentage: Int, val month: Int, val remainingBalanceCents: Long)

data class DebtStrategyComparison(val strategy: DebtStrategy, val projection: DebtProjection?)

data class DebtCheckIn(
    val id: String,
    val month: String,
    val recordedOn: String,
    val balances: List<DebtCheckInBalance>,
) {
    val totalBalanceCents: Long get() = balances.sumOf(DebtCheckInBalance::balanceCents)
}

data class DebtCheckInBalance(
    val debtId: String,
    val balanceCents: Long,
    val annualInterestBps: Int,
    val minimumPaymentCents: Long,
    val inArrears: Boolean,
)

data class DebtSpendingMonth(
    val month: String,
    val categoryId: String?,
    val categoryName: String?,
    val systemKey: String?,
    val spentCents: Long,
)

data class DebtSpendingSuggestion(
    val categoryId: String,
    val categoryName: String,
    val medianMonthlySpendCents: Long,
)

data class DebtSpendingAnalysis(
    val monthCount: Int,
    val coveragePercentage: Int,
    val suggestions: List<DebtSpendingSuggestion>,
) {
    val isReady: Boolean get() = monthCount >= 2 && coveragePercentage >= 75
}

data class DebtPlan(
    val strategy: DebtStrategy,
    val title: String,
    val reason: String,
    val totalBalanceCents: Long,
    val minimumPaymentsCents: Long,
    val monthlyIncomeCents: Long,
    val monthlyLivingPlanCents: Long,
    val availableForDebtCents: Long,
    val extraPaymentCents: Long,
    val payoffOrder: List<Debt>,
    val projection: DebtProjection?,
    val comparisons: List<DebtStrategyComparison>,
    val warnings: List<String>,
)

private data class SimulationDebt(
    val debt: Debt,
    var balanceCents: Long,
)

fun MobileDashboard.debtSpendingAnalysis(): DebtSpendingAnalysis {
    val months = debtSpendingHistory.map(DebtSpendingMonth::month).distinct().sorted()
    val total = debtSpendingHistory.sumOf(DebtSpendingMonth::spentCents)
    val categorised = debtSpendingHistory.filter { it.categoryId != null }.sumOf(DebtSpendingMonth::spentCents)
    val allowedCategories = budgets.mapTo(mutableSetOf(), Budget::categoryId)
    val excludedSystemKeys = setOf("debt", "debt_payment", "savings", "transfer", "income")
    val suggestions = debtSpendingHistory
        .filter { it.categoryId != null && it.categoryId in allowedCategories && it.systemKey !in excludedSystemKeys }
        .groupBy { it.categoryId!! }
        .mapNotNull { (categoryId, rows) ->
            val name = rows.firstNotNullOfOrNull(DebtSpendingMonth::categoryName) ?: return@mapNotNull null
            val byMonth = rows.associate { it.month to it.spentCents }
            val values = months.map { byMonth[it] ?: 0 }.sorted()
            if (values.isEmpty()) return@mapNotNull null
            val median = if (values.size % 2 == 1) values[values.size / 2] else {
                (values[values.size / 2 - 1] + values[values.size / 2]) / 2
            }
            DebtSpendingSuggestion(categoryId, name, median)
        }
        .filter { it.medianMonthlySpendCents > 0 }
        .sortedByDescending(DebtSpendingSuggestion::medianMonthlySpendCents)
    return DebtSpendingAnalysis(
        monthCount = months.size,
        coveragePercentage = if (total <= 0) 0 else ((categorised * 100) / total).coerceIn(0, 100).toInt(),
        suggestions = suggestions,
    )
}

fun MobileDashboard.debtPlan(
    strategyOverride: DebtStrategy? = null,
    additionalMonthlyPaymentCents: Long = 0,
): DebtPlan? {
    val activeDebts = debts.filter { it.isActive && it.balanceCents > 0 }
    if (activeDebts.isEmpty()) return null

    val income = plannedItems
        .filter { it.direction == "income" }
        .sumOf(PlannedItem::plannedCents)
    val livingItems = plannedItems.filter {
        it.direction == "expense" && it.kind != "debt_payment"
    }
    val categorised = livingItems.filter { it.categoryId != null }.groupBy { it.categoryId!! }
    val budgetByCategory = budgets.associateBy(Budget::categoryId)
    val categoryIds = categorised.keys + budgetByCategory.keys
    val categoryPlan = categoryIds.sumOf { categoryId ->
        maxOf(
            categorised[categoryId].orEmpty().sumOf(PlannedItem::plannedCents),
            budgetByCategory[categoryId]?.limitCents ?: 0,
        )
    }
    val uncategorisedPlan = livingItems.filter { it.categoryId == null }.sumOf(PlannedItem::plannedCents)
    val livingPlan = categoryPlan + uncategorisedPlan
    val availableForDebt = runCatching {
        Math.addExact(income - livingPlan, additionalMonthlyPaymentCents.coerceAtLeast(0))
    }.getOrElse { return null }
    val enteredMinimums = activeDebts.sumOf(Debt::minimumPaymentCents)
    val plannedDebtPayments = plannedItems
        .filter { it.direction == "expense" && it.kind == "debt_payment" }
        .sumOf(PlannedItem::plannedCents)
    val extra = (availableForDebt - enteredMinimums).coerceAtLeast(0)
    val totalBalance = activeDebts.sumOf(Debt::balanceCents)
    val warnings = mutableListOf<String>()

    if (plannedItems.none { it.direction == "income" }) {
        warnings += "Add your monthly income to the period plan so this estimate can use a realistic repayment capacity."
    }
    if (activeDebts.any(Debt::secured)) {
        warnings += "Secured debts can put an asset at risk. Get professional advice before changing those payments."
    }
    if (plannedDebtPayments > enteredMinimums) {
        warnings += "Your period plan contains more debt payments than the minimums entered here. This projection uses income-based capacity and may differ from that plan."
    }
    if (plannedDebtPayments > availableForDebt) {
        warnings += "Planned debt-payment lines exceed the monthly capacity estimated here. Review those lines or add any missing debts and minimums."
    }

    val formalSupportTitle = if (entity.kind == "personal") {
        "Debt counselling / management plan"
    } else {
        "Professional debt restructuring"
    }
    if (activeDebts.any(Debt::inArrears) || availableForDebt < enteredMinimums) {
        val reason = if (activeDebts.any(Debt::inArrears)) {
            if (entity.kind == "personal") {
                "At least one debt is in arrears. A registered counsellor can assess the full position and contact credit providers through the proper process."
            } else {
                "At least one debt is in arrears. A qualified restructuring or insolvency professional should assess the business and its obligations."
            }
        } else {
            "The current monthly plan leaves ${formatZar(availableForDebt.coerceAtLeast(0))} for debt, below entered minimum payments of ${formatZar(enteredMinimums)}."
        }
        return DebtPlan(
            strategy = DebtStrategy.FORMAL_SUPPORT,
            title = formalSupportTitle,
            reason = reason,
            totalBalanceCents = totalBalance,
            minimumPaymentsCents = enteredMinimums,
            monthlyIncomeCents = income,
            monthlyLivingPlanCents = livingPlan,
            availableForDebtCents = availableForDebt,
            extraPaymentCents = 0,
            payoffOrder = activeDebts.sortedByDescending(Debt::annualInterestBps),
            projection = null,
            comparisons = emptyList(),
            warnings = warnings,
        )
    }

    val avalancheOrder = activeDebts.sortedWith(
        compareByDescending<Debt> { it.annualInterestBps }.thenBy { it.balanceCents },
    )
    val avalanche = simulateDebtPayoff(avalancheOrder, availableForDebt)
    val consolidation = consolidationProjection(totalBalance, debtPreferences)
    val consolidationCost = consolidation?.let { safeAdd(totalBalance, it.totalInterestCents) }
    val avalancheCost = avalanche?.let { safeAdd(totalBalance, it.totalInterestCents) }
    val consolidationPayment = consolidationMonthlyPayment(totalBalance, debtPreferences)
    val consolidationWins = consolidation != null && avalancheCost != null && consolidationCost != null &&
        consolidationPayment != null && consolidationPayment <= availableForDebt &&
        avalancheCost - consolidationCost >= maxOf(10_000L, avalancheCost / 20)

    val recommendedStrategy = when {
        avalanche == null -> DebtStrategy.FORMAL_SUPPORT
        consolidationWins && debtPreferences.preferredStrategy == "recommended" -> DebtStrategy.CONSOLIDATION_REVIEW
        debtPreferences.goal == "quick_wins" -> DebtStrategy.SNOWBALL
        debtPreferences.goal == "balanced" && activeDebts.size > 1 -> DebtStrategy.HYBRID
        else -> DebtStrategy.AVALANCHE
    }
    val storedStrategy = when (debtPreferences.preferredStrategy) {
        "avalanche" -> DebtStrategy.AVALANCHE
        "snowball" -> DebtStrategy.SNOWBALL
        "hybrid" -> DebtStrategy.HYBRID
        else -> null
    }
    val strategy = (strategyOverride ?: storedStrategy)?.takeIf {
        it == DebtStrategy.AVALANCHE || it == DebtStrategy.SNOWBALL || it == DebtStrategy.HYBRID
    } ?: recommendedStrategy
    val order = when (strategy) {
        DebtStrategy.SNOWBALL -> activeDebts.sortedWith(compareBy<Debt> { it.balanceCents }.thenByDescending { it.annualInterestBps })
        DebtStrategy.HYBRID -> {
            val first = activeDebts.minBy(Debt::balanceCents)
            listOf(first) + activeDebts.filterNot { it.id == first.id }
                .sortedWith(compareByDescending<Debt> { it.annualInterestBps }.thenBy { it.balanceCents })
        }
        else -> avalancheOrder
    }
    val projection = when (strategy) {
        DebtStrategy.CONSOLIDATION_REVIEW -> consolidation
        DebtStrategy.FORMAL_SUPPORT -> null
        else -> simulateDebtPayoff(order, availableForDebt)
    }
    val title = when (strategy) {
        DebtStrategy.AVALANCHE -> "Debt avalanche"
        DebtStrategy.SNOWBALL -> "Debt snowball"
        DebtStrategy.HYBRID -> "Quick win, then avalanche"
        DebtStrategy.CONSOLIDATION_REVIEW -> "Consolidation review"
        DebtStrategy.FORMAL_SUPPORT -> formalSupportTitle
    }
    val reason = when (strategy) {
        DebtStrategy.AVALANCHE -> "Pay minimums on every debt, then direct ${formatZar(extra)} extra to the highest interest rate. This normally minimises interest."
        DebtStrategy.SNOWBALL -> "Pay minimums on every debt, then clear the smallest balance first. Each cleared debt creates a visible win and frees its payment for the next one."
        DebtStrategy.HYBRID -> "Clear the smallest balance first for momentum, then switch the rolled-up payment to the highest interest rate."
        DebtStrategy.CONSOLIDATION_REVIEW -> "The offer you entered is projected to cost at least 5% less than the avalanche estimate and its calculated payment fits the current plan. Verify fees and terms before accepting it."
        DebtStrategy.FORMAL_SUPPORT -> "The current payment plan does not reliably reduce every balance. A registered professional should assess affordable next steps."
    }
    if (projection == null) {
        warnings += "The entered payments do not clear every balance within 50 years at the stated rates."
    }

    return DebtPlan(
        strategy = strategy,
        title = title,
        reason = reason,
        totalBalanceCents = totalBalance,
        minimumPaymentsCents = enteredMinimums,
        monthlyIncomeCents = income,
        monthlyLivingPlanCents = livingPlan,
        availableForDebtCents = availableForDebt,
        extraPaymentCents = extra,
        payoffOrder = order,
        projection = projection,
        comparisons = strategyComparisons(activeDebts, availableForDebt, debtPreferences),
        warnings = warnings,
    )
}

internal fun simulateDebtPayoff(order: List<Debt>, monthlyBudgetCents: Long): DebtProjection? {
    if (order.isEmpty()) return DebtProjection(
        payoffMonths = 0,
        totalInterestCents = 0,
        monthlyPaymentCents = 0,
        trajectory = listOf(DebtTrajectoryPoint(0, 0, 0, null, null)),
        payoffEvents = emptyList(),
        milestones = emptyList(),
    )
    if (monthlyBudgetCents < order.sumOf(Debt::minimumPaymentCents)) return null

    val states = order.map { SimulationDebt(it, it.balanceCents) }
    val originalBalance = order.sumOf(Debt::balanceCents)
    var totalInterest = 0L
    val trajectory = mutableListOf(DebtTrajectoryPoint(0, originalBalance, 0, order.firstOrNull()?.id, order.firstOrNull()?.name))
    val payoffEvents = mutableListOf<DebtPayoffEvent>()
    val milestones = mutableListOf<DebtMilestone>()
    val reachedMilestones = mutableSetOf<Int>()
    for (month in 1..600) {
        for (state in states.filter { it.balanceCents > 0 }) {
            val interest = runCatching {
                BigDecimal.valueOf(state.balanceCents)
                .multiply(BigDecimal.valueOf(state.debt.annualInterestBps.toLong()))
                .divide(BigDecimal.valueOf(120_000L), 0, RoundingMode.HALF_UP)
                .longValueExact()
            }.getOrNull() ?: return null
            state.balanceCents = runCatching { Math.addExact(state.balanceCents, interest) }.getOrNull()
                ?: return null
            totalInterest = runCatching { Math.addExact(totalInterest, interest) }.getOrNull()
                ?: return null
        }

        var available = monthlyBudgetCents
        val beforePayment = states.associate { it.debt.id to it.balanceCents }
        states.filter { it.balanceCents > 0 }.forEach { state ->
            val payment = minOf(state.debt.minimumPaymentCents, state.balanceCents, available)
            state.balanceCents -= payment
            available -= payment
        }
        states.filter { it.balanceCents > 0 }.forEach { state ->
            if (available <= 0) return@forEach
            val payment = minOf(state.balanceCents, available)
            state.balanceCents -= payment
            available -= payment
        }

        states.forEach { state ->
            if ((beforePayment[state.debt.id] ?: 0) > 0 && state.balanceCents <= 0) {
                payoffEvents += DebtPayoffEvent(state.debt.id, state.debt.name, month)
            }
        }
        val remaining = states.sumOf { it.balanceCents.coerceAtLeast(0) }
        val target = states.firstOrNull { it.balanceCents > 0 }?.debt
        trajectory += DebtTrajectoryPoint(month, remaining, totalInterest, target?.id, target?.name)
        val repaidPercentage = if (originalBalance <= 0) 100 else {
            (((originalBalance - remaining).toDouble() / originalBalance.toDouble()) * 100).toInt()
        }
        listOf(25, 50, 75, 100).forEach { percentage ->
            if (repaidPercentage >= percentage && reachedMilestones.add(percentage)) {
                milestones += DebtMilestone(percentage, month, remaining)
            }
        }

        if (states.all { it.balanceCents <= 0 }) return DebtProjection(
            payoffMonths = month,
            totalInterestCents = totalInterest,
            monthlyPaymentCents = monthlyBudgetCents,
            trajectory = trajectory,
            payoffEvents = payoffEvents,
            milestones = milestones,
        )
    }
    return null
}

internal fun strategyComparisons(
    debts: List<Debt>,
    monthlyBudgetCents: Long,
    preferences: DebtPreferences,
): List<DebtStrategyComparison> {
    val avalanche = debts.sortedWith(compareByDescending<Debt> { it.annualInterestBps }.thenBy { it.balanceCents })
    val snowball = debts.sortedWith(compareBy<Debt> { it.balanceCents }.thenByDescending { it.annualInterestBps })
    val smallest = debts.minByOrNull(Debt::balanceCents)
    val hybrid = if (smallest == null) emptyList() else listOf(smallest) + debts.filterNot { it.id == smallest.id }
        .sortedWith(compareByDescending<Debt> { it.annualInterestBps }.thenBy { it.balanceCents })
    return buildList {
        add(DebtStrategyComparison(DebtStrategy.AVALANCHE, simulateDebtPayoff(avalanche, monthlyBudgetCents)))
        add(DebtStrategyComparison(DebtStrategy.SNOWBALL, simulateDebtPayoff(snowball, monthlyBudgetCents)))
        add(DebtStrategyComparison(DebtStrategy.HYBRID, simulateDebtPayoff(hybrid, monthlyBudgetCents)))
        consolidationProjection(debts.sumOf(Debt::balanceCents), preferences)?.let {
            add(DebtStrategyComparison(DebtStrategy.CONSOLIDATION_REVIEW, it))
        }
    }
}

private fun consolidationMonthlyPayment(totalBalanceCents: Long, preferences: DebtPreferences): Long? {
    val apr = preferences.consolidationAprBps ?: return null
    val months = preferences.consolidationTermMonths ?: return null
    val principal = totalBalanceCents.toDouble()
    val monthlyRate = apr.toDouble() / 120_000.0
    val payment = if (monthlyRate == 0.0) {
        principal / months
    } else {
        principal * monthlyRate / (1.0 - (1.0 + monthlyRate).pow(-months))
    }
    return runCatching {
        BigDecimal.valueOf(payment).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()
}

private fun consolidationProjection(totalBalanceCents: Long, preferences: DebtPreferences): DebtProjection? {
    val months = preferences.consolidationTermMonths ?: return null
    val payment = consolidationMonthlyPayment(totalBalanceCents, preferences) ?: return null
    val totalCost = runCatching {
        Math.addExact(
            Math.multiplyExact(payment, months.toLong()),
            preferences.consolidationFeesCents,
        )
    }.getOrNull() ?: return null
    val interest = (totalCost - totalBalanceCents).coerceAtLeast(0)
    return DebtProjection(
        payoffMonths = months,
        totalInterestCents = interest,
        monthlyPaymentCents = payment,
        trajectory = (0..months).map { month ->
            val progress = month.toDouble() / months.toDouble()
            DebtTrajectoryPoint(
                month,
                (totalBalanceCents * (1.0 - progress)).toLong().coerceAtLeast(0),
                (interest * progress).toLong().coerceAtLeast(0),
                null,
                "Consolidation loan",
            )
        },
        payoffEvents = listOf(DebtPayoffEvent("consolidation", "Consolidation loan", months)),
        milestones = listOf(25, 50, 75, 100).map { percentage ->
            DebtMilestone(
                percentage,
                maxOf(1, kotlin.math.ceil(months * percentage / 100.0).toInt()),
                (totalBalanceCents * (1.0 - percentage / 100.0)).toLong().coerceAtLeast(0),
            )
        },
    )
}

private fun safeAdd(left: Long, right: Long): Long? =
    runCatching { Math.addExact(left, right) }.getOrNull()

fun debtTypeLabel(type: String): String = when (type) {
    "credit_card" -> "Credit card"
    "personal_loan" -> "Personal loan"
    "overdraft" -> "Overdraft"
    "vehicle_finance" -> "Vehicle finance"
    "home_loan" -> "Home loan"
    "store_account" -> "Store account"
    "tax" -> "Tax debt"
    "medical" -> "Medical debt"
    else -> "Other debt"
}
