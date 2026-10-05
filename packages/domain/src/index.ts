export type TransactionStatus = "pending" | "posted" | "reversed" | "failed";
export type TransactionKind =
  | "card_purchase"
  | "transfer"
  | "scheduled_payment"
  | "cash_withdrawal"
  | "fee"
  | "income"
  | "reversal"
  | "other";
export type TransactionSource = "sms" | "statement" | "manual" | "bank_api";

export type AccountRole = "operational" | "savings" | "liability" | "rewards";
export type AccountType =
  | "cheque"
  | "savings"
  | "credit_card"
  | "home_loan"
  | "rewards"
  | "cash";

export interface Account {
  id: string;
  name: string;
  type: AccountType;
  role: AccountRole;
  purpose: string;
  mask: string;
  currentBalanceCents: number;
  creditLimitCents: number | null;
  includeInSafeToSpend: boolean;
  displayOrder: number;
}

export interface Category {
  id: string;
  name: string;
  colour: string;
  icon: string;
}

export interface Budget {
  id: string;
  categoryId: string;
  limitCents: number;
  spentCents: number;
  committedCents: number;
}

export interface Transaction {
  id: string;
  accountId: string;
  categoryId: string | null;
  occurredAt: string;
  amountCents: number;
  status: TransactionStatus;
  kind: TransactionKind;
  source: TransactionSource;
  merchant: string;
  description: string;
  needsReview: boolean;
  plannedItemIds: string[];
}

export function isUnplannedPayment(transaction: Transaction): boolean {
  return transaction.amountCents < 0
    && transaction.categoryId === null
    && transaction.plannedItemIds.length === 0
    && !["transfer", "reversal"].includes(transaction.kind);
}

export interface BudgetPeriod {
  id: string;
  startsOn: string;
  status: "draft" | "active" | "closed";
  carryoverCents: number;
  cycleDay?: number;
}

export interface BudgetCycleBounds {
  startsOn: Date;
  endsOnExclusive: Date;
}

function assertCycleDay(cycleDay: number) {
  if (!Number.isInteger(cycleDay) || cycleDay < 1 || cycleDay > 28) {
    throw new RangeError("Budget cycle day must be between 1 and 28.");
  }
}

export function budgetPeriodLabelFor(date: Date, cycleDay: number): string {
  assertCycleDay(cycleDay);
  const movesToNextMonth = cycleDay > 1 && date.getDate() >= cycleDay;
  const label = new Date(date.getFullYear(), date.getMonth() + (movesToNextMonth ? 1 : 0), 1, 12);
  return `${label.getFullYear()}-${String(label.getMonth() + 1).padStart(2, "0")}-01`;
}

export function budgetCycleBounds(periodLabelStart: string, cycleDay: number): BudgetCycleBounds {
  assertCycleDay(cycleDay);
  const parts = periodLabelStart.split("-").map(Number);
  const year = parts[0];
  const month = parts[1];
  if (year === undefined || month === undefined || !year || month < 1 || month > 12) {
    throw new RangeError("Invalid budget period label.");
  }
  const startsOn = cycleDay === 1
    ? new Date(year, month - 1, 1, 12)
    : new Date(year, month - 2, cycleDay, 12);
  const endsOnExclusive = cycleDay === 1
    ? new Date(year, month, 1, 12)
    : new Date(year, month - 1, cycleDay, 12);
  return { startsOn, endsOnExclusive };
}

export function formatBudgetPeriodRange(periodLabelStart: string, cycleDay: number): string {
  if (cycleDay === 1) {
    const parts = periodLabelStart.split("-").map(Number);
    const year = parts[0];
    const month = parts[1];
    if (year === undefined || month === undefined || !year || month < 1 || month > 12) {
      throw new RangeError("Invalid budget period label.");
    }
    const start = new Date(year, month - 1, 1, 12);
    const end = new Date(year, month, 1, 12);
    const startName = new Intl.DateTimeFormat("en-ZA", { month: "long" }).format(start);
    const endName = new Intl.DateTimeFormat("en-ZA", { month: "long", year: "numeric" }).format(end);
    return `${startName} – ${endName}`;
  }
  const bounds = budgetCycleBounds(periodLabelStart, cycleDay);
  const inclusiveEnd = new Date(bounds.endsOnExclusive);
  inclusiveEnd.setDate(inclusiveEnd.getDate() - 1);
  const start = new Intl.DateTimeFormat("en-ZA", { day: "numeric", month: "short" }).format(bounds.startsOn);
  const end = new Intl.DateTimeFormat("en-ZA", { day: "numeric", month: "short", year: "numeric" }).format(inclusiveEnd);
  return `${start} – ${end}`;
}

export type PlannedItemDirection = "income" | "expense";
export type PlannedItemKind =
  | "income"
  | "fixed_expense"
  | "variable_expense"
  | "savings"
  | "debt_payment";

export interface PlannedItem {
  id: string;
  direction: PlannedItemDirection;
  kind: PlannedItemKind;
  name: string;
  plannedCents: number;
  actualCents: number;
  accountId: string | null;
  categoryId: string | null;
  dueDay: number | null;
  sortOrder: number;
  manuallyPaid?: boolean;
}

export type ExpenseOrder = "name" | "amount";

export interface PlannedItemGroups {
  income: PlannedItem[];
  unpaidExpenses: PlannedItem[];
  paidExpenses: PlannedItem[];
}

export interface DashboardData {
  month: string;
  period: BudgetPeriod;
  accounts: Account[];
  categories: Category[];
  budgets: Budget[];
  plannedItems: PlannedItem[];
  transactions: Transaction[];
}

export interface CashflowSummary {
  plannedIncomeCents: number;
  plannedExpenseCents: number;
  actualIncomeCents: number;
  actualExpenseCents: number;
  projectedSurplusCents: number;
  remainingToFundCents: number;
  allocationPercentage: number;
}

export function summariseCashflow(
  period: BudgetPeriod,
  items: PlannedItem[],
): CashflowSummary {
  const income = items.filter((item) => item.direction === "income");
  const expenses = items.filter((item) => item.direction === "expense");
  const plannedIncomeCents = income.reduce((sum, item) => sum + item.plannedCents, 0);
  const plannedExpenseCents = expenses.reduce((sum, item) => sum + item.plannedCents, 0);
  const actualIncomeCents = income.reduce((sum, item) => sum + item.actualCents, 0);
  const actualExpenseCents = expenses.reduce(
    (sum, item) => sum + (item.manuallyPaid ? Math.max(item.actualCents, item.plannedCents) : item.actualCents),
    0,
  );
  const availableCents = period.carryoverCents + plannedIncomeCents;

  return {
    plannedIncomeCents,
    plannedExpenseCents,
    actualIncomeCents,
    actualExpenseCents,
    projectedSurplusCents: availableCents - plannedExpenseCents,
    remainingToFundCents: Math.max(0, plannedExpenseCents - actualExpenseCents),
    allocationPercentage: availableCents > 0
      ? Math.round((plannedExpenseCents / availableCents) * 100)
      : 0,
  };
}

export function plannedItemStatus(item: PlannedItem): "expected" | "partial" | "settled" {
  if (item.manuallyPaid) return "settled";
  if (item.actualCents >= item.plannedCents) return "settled";
  if (item.actualCents > 0) return "partial";
  return "expected";
}

export function groupPlannedItems(
  items: PlannedItem[],
  query: string,
  expenseOrder: ExpenseOrder,
): PlannedItemGroups {
  const needle = query.trim().toLocaleLowerCase("en-ZA");
  const visible = needle
    ? items.filter((item) => item.name.toLocaleLowerCase("en-ZA").includes(needle))
    : [...items];
  const byPlanOrder = (left: PlannedItem, right: PlannedItem) =>
    left.sortOrder - right.sortOrder || left.name.localeCompare(right.name, "en-ZA");
  const byExpenseOrder = expenseOrder === "amount"
    ? (left: PlannedItem, right: PlannedItem) =>
        right.plannedCents - left.plannedCents || left.name.localeCompare(right.name, "en-ZA")
    : (left: PlannedItem, right: PlannedItem) => left.name.localeCompare(right.name, "en-ZA");
  const expenses = visible.filter((item) => item.direction === "expense").sort(byExpenseOrder);

  return {
    income: visible.filter((item) => item.direction === "income").sort(byPlanOrder),
    unpaidExpenses: expenses.filter((item) => plannedItemStatus(item) !== "settled"),
    paidExpenses: expenses.filter((item) => plannedItemStatus(item) === "settled"),
  };
}

export interface BudgetSummary {
  limitCents: number;
  spentCents: number;
  committedCents: number;
  remainingCents: number;
  safeToSpendTodayCents: number;
  daysRemaining: number;
}

export function summariseBudgets(
  budgets: Budget[],
  now = new Date(),
  period?: Pick<BudgetPeriod, "startsOn" | "cycleDay">,
): BudgetSummary {
  const limitCents = budgets.reduce((sum, budget) => sum + budget.limitCents, 0);
  const spentCents = budgets.reduce((sum, budget) => sum + budget.spentCents, 0);
  const committedCents = budgets.reduce(
    (sum, budget) => sum + budget.committedCents,
    0,
  );
  const effectiveSpend = spentCents + committedCents;
  const remainingCents = Math.max(0, limitCents - effectiveSpend);
  const daysRemaining = period
    ? daysRemainingInBudgetCycle(period.startsOn, period.cycleDay ?? 1, now)
    : Math.max(1, new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate() - now.getDate() + 1);

  return {
    limitCents,
    spentCents,
    committedCents,
    remainingCents,
    safeToSpendTodayCents: Math.floor(remainingCents / daysRemaining),
    daysRemaining,
  };
}

export function daysRemainingInBudgetCycle(periodLabelStart: string, cycleDay: number, today: Date): number {
  const bounds = budgetCycleBounds(periodLabelStart, cycleDay);
  const day = new Date(today.getFullYear(), today.getMonth(), today.getDate(), 12);
  const millisecondsPerDay = 86_400_000;
  if (day < bounds.startsOn) {
    return Math.round((bounds.endsOnExclusive.getTime() - bounds.startsOn.getTime()) / millisecondsPerDay);
  }
  if (day >= bounds.endsOnExclusive) return 1;
  return Math.max(1, Math.round((bounds.endsOnExclusive.getTime() - day.getTime()) / millisecondsPerDay));
}

export function budgetPercentage(budget: Budget): number {
  if (budget.limitCents <= 0) return 0;
  return Math.min(
    100,
    Math.round(((budget.spentCents + budget.committedCents) / budget.limitCents) * 100),
  );
}

export function formatZar(cents: number, options?: { sign?: boolean }): string {
  return new Intl.NumberFormat("en-ZA", {
    style: "currency",
    currency: "ZAR",
    signDisplay: options?.sign ? "always" : "auto",
    maximumFractionDigits: 2,
  }).format(cents / 100);
}

export interface SafeToSpendInput {
  accounts: Account[];
  plannedItems: PlannedItem[];
  transactions: Transaction[];
}

export interface SafeToSpendSummary {
  /** B — total SMS available balances for STS accounts */
  bCents: number;
  /** R — remaining planned outflows after subtracting stored match allocations */
  rCents: number;
  /** P — all pending outflows on STS accounts (matched + unmatched; excludes transfer/reversal) */
  pCents: number;
  /** C = R + P (total commitments) */
  cCents: number;
  /** STS = B − C (may be negative, not clamped) */
  safeToSpendCents: number;
}

export function summariseSafeToSpend(input: SafeToSpendInput): SafeToSpendSummary {
  const { accounts, plannedItems, transactions } = input;

  const bCents = accounts
    .filter((acc) => acc.includeInSafeToSpend)
    .reduce((sum, acc) => sum + acc.currentBalanceCents, 0);

  const rCents = plannedItems
    .filter((item) => item.direction === "expense")
    .reduce((sum, item) => {
      // planned_item_progress.actual_cents is the authoritative sum of match
      // allocations. A transaction link alone, a category, or "mark paid"
      // must not change the bank-position calculation.
      const matched = Math.max(0, item.actualCents);
      const remaining = Math.max(0, item.plannedCents - matched);
      return sum + remaining;
    }, 0);

  const stsAccountIds = new Set(
    accounts.filter((acc) => acc.includeInSafeToSpend).map((acc) => acc.id),
  );

  const pCents = transactions
    .filter(
      (tx) =>
        tx.status === "pending" &&
        tx.amountCents < 0 &&
        !["transfer", "reversal"].includes(tx.kind) &&
        stsAccountIds.has(tx.accountId),
    )
    .reduce((sum, tx) => sum + Math.abs(tx.amountCents), 0);

  const cCents = rCents + pCents;
  const safeToSpendCents = bCents - cCents;

  return {
    bCents,
    rCents,
    pCents,
    cCents,
    safeToSpendCents,
  };
}

export interface Debt {
  id: string;
  name: string;
  type: string;
  balanceCents: number;
  annualInterestBps: number;
  minimumPaymentCents: number;
  remainingTermMonths: number | null;
  dueDay: number | null;
  secured: boolean;
  inArrears: boolean;
  isActive?: boolean;
  closedReason?: "paid_off" | "archived" | null;
  closedAt?: string | null;
}

export interface DebtPreferences {
  goal: "lowest_cost" | "quick_wins" | "balanced";
  preferredStrategy?: "recommended" | "avalanche" | "snowball" | "hybrid";
  consolidationAprBps: number | null;
  consolidationTermMonths: number | null;
  consolidationFeesCents: number;
  reminderEnabled?: boolean;
  reminderDay?: number;
}

export type DebtStrategy =
  | "avalanche"
  | "snowball"
  | "hybrid"
  | "consolidation_review"
  | "formal_support";

export interface DebtProjection {
  payoffMonths: number;
  totalInterestCents: number;
  monthlyPaymentCents: number;
  trajectory: DebtTrajectoryPoint[];
  payoffEvents: DebtPayoffEvent[];
  milestones: DebtMilestone[];
}

export interface DebtTrajectoryPoint {
  month: number;
  remainingBalanceCents: number;
  cumulativeInterestCents: number;
  targetDebtId: string | null;
  targetDebtName: string | null;
}

export interface DebtPayoffEvent {
  debtId: string;
  debtName: string;
  month: number;
}

export interface DebtMilestone {
  percentage: 25 | 50 | 75 | 100;
  month: number;
  remainingBalanceCents: number;
}

export interface DebtStrategyComparison {
  strategy: Exclude<DebtStrategy, "formal_support">;
  projection: DebtProjection | null;
}

export interface DebtPlanInput {
  debts: Debt[];
  preferences: DebtPreferences;
  plannedItems: PlannedItem[];
  budgets: Budget[];
  additionalMonthlyPaymentCents?: number;
}

export interface DebtPlan {
  strategy: DebtStrategy;
  totalBalanceCents: number;
  minimumPaymentsCents: number;
  monthlyIncomeCents: number;
  monthlyLivingPlanCents: number;
  availableForDebtCents: number;
  extraPaymentCents: number;
  payoffOrder: Debt[];
  projection: DebtProjection | null;
  comparisons: DebtStrategyComparison[];
}

export function recommendDebtStrategy(input: DebtPlanInput): DebtPlan | null {
  const { preferences, plannedItems, budgets } = input;
  const debts = input.debts.filter((debt) => debt.isActive !== false && debt.balanceCents > 0);
  if (debts.length === 0) return null;

  const monthlyIncomeCents = plannedItems
    .filter((item) => item.direction === "income")
    .reduce((sum, item) => sum + item.plannedCents, 0);
  const livingItems = plannedItems.filter(
    (item) => item.direction === "expense" && item.kind !== "debt_payment",
  );
  const categoryIds = new Set([
    ...livingItems.flatMap((item) => item.categoryId ? [item.categoryId] : []),
    ...budgets.map((budget) => budget.categoryId),
  ]);
  const categoryPlan = [...categoryIds].reduce((sum, categoryId) => {
    const planned = livingItems
      .filter((item) => item.categoryId === categoryId)
      .reduce((categorySum, item) => categorySum + item.plannedCents, 0);
    const limit = budgets.find((budget) => budget.categoryId === categoryId)?.limitCents ?? 0;
    return sum + Math.max(planned, limit);
  }, 0);
  const uncategorisedPlan = livingItems
    .filter((item) => item.categoryId === null)
    .reduce((sum, item) => sum + item.plannedCents, 0);
  const monthlyLivingPlanCents = categoryPlan + uncategorisedPlan;
  const availableForDebtCents = monthlyIncomeCents - monthlyLivingPlanCents
    + Math.max(0, input.additionalMonthlyPaymentCents ?? 0);
  const minimumPaymentsCents = debts.reduce((sum, debt) => sum + debt.minimumPaymentCents, 0);
  const totalBalanceCents = debts.reduce((sum, debt) => sum + debt.balanceCents, 0);
  const base = {
    totalBalanceCents,
    minimumPaymentsCents,
    monthlyIncomeCents,
    monthlyLivingPlanCents,
    availableForDebtCents,
    extraPaymentCents: Math.max(0, availableForDebtCents - minimumPaymentsCents),
  };

  const avalancheOrder = [...debts].sort(
    (left, right) => right.annualInterestBps - left.annualInterestBps || left.balanceCents - right.balanceCents,
  );
  if (
    debts.some((debt) => debt.inArrears)
    || availableForDebtCents < minimumPaymentsCents
  ) {
    return {
      ...base,
      strategy: "formal_support",
      payoffOrder: avalancheOrder,
      projection: null,
      comparisons: [],
    };
  }

  const avalanche = simulateDebtPayoff(avalancheOrder, availableForDebtCents);
  if (avalanche === null) {
    return {
      ...base,
      strategy: "formal_support",
      payoffOrder: avalancheOrder,
      projection: null,
      comparisons: [],
    };
  }

  const consolidation = consolidationProjection(totalBalanceCents, preferences);
  const consolidationPayment = consolidationMonthlyPayment(totalBalanceCents, preferences);
  const avalancheCost = totalBalanceCents + avalanche.totalInterestCents;
  const consolidationCost = consolidation === null
    ? null
    : totalBalanceCents + consolidation.totalInterestCents;
  const consolidationWins = consolidation !== null
    && consolidationCost !== null
    && consolidationPayment !== null
    && consolidationPayment <= availableForDebtCents
    && Number.isSafeInteger(avalancheCost)
    && Number.isSafeInteger(consolidationCost)
    && avalancheCost - consolidationCost
      >= Math.max(10_000, Math.round(avalancheCost / 20));
  if (consolidationWins && (preferences.preferredStrategy ?? "recommended") === "recommended") {
    return {
      ...base,
      strategy: "consolidation_review",
      payoffOrder: avalancheOrder,
      projection: consolidation,
      comparisons: strategyComparisons(debts, availableForDebtCents, preferences),
    };
  }

  const preferred = preferences.preferredStrategy;
  const strategy: DebtStrategy = preferred && preferred !== "recommended"
    ? preferred
    : preferences.goal === "quick_wins"
      ? "snowball"
      : preferences.goal === "balanced" && debts.length > 1
        ? "hybrid"
        : "avalanche";
  const payoffOrder = strategy === "snowball"
    ? [...debts].sort((left, right) => left.balanceCents - right.balanceCents || right.annualInterestBps - left.annualInterestBps)
    : strategy === "hybrid"
      ? hybridDebtOrder(debts)
      : avalancheOrder;

  return {
    ...base,
    strategy,
    payoffOrder,
    projection: simulateDebtPayoff(payoffOrder, availableForDebtCents),
    comparisons: strategyComparisons(debts, availableForDebtCents, preferences),
  };
}

export function simulateDebtPayoff(order: Debt[], monthlyBudgetCents: number): DebtProjection | null {
  if (order.length === 0) {
    return {
      payoffMonths: 0,
      totalInterestCents: 0,
      monthlyPaymentCents: 0,
      trajectory: [{ month: 0, remainingBalanceCents: 0, cumulativeInterestCents: 0, targetDebtId: null, targetDebtName: null }],
      payoffEvents: [],
      milestones: [],
    };
  }
  if (monthlyBudgetCents < order.reduce((sum, debt) => sum + debt.minimumPaymentCents, 0)) return null;
  const states = order.map((debt) => ({ debt, balanceCents: debt.balanceCents }));
  const originalBalanceCents = order.reduce((sum, debt) => sum + debt.balanceCents, 0);
  let totalInterestCents = 0;
  const trajectory: DebtTrajectoryPoint[] = [{
    month: 0,
    remainingBalanceCents: originalBalanceCents,
    cumulativeInterestCents: 0,
    targetDebtId: order[0]?.id ?? null,
    targetDebtName: order[0]?.name ?? null,
  }];
  const payoffEvents: DebtPayoffEvent[] = [];
  const reachedMilestones = new Set<number>();
  const milestones: DebtMilestone[] = [];
  for (let month = 1; month <= 600; month += 1) {
    for (const state of states) {
      if (state.balanceCents <= 0) continue;
      const interest = Math.round((state.balanceCents * state.debt.annualInterestBps) / 120_000);
      const nextBalance = state.balanceCents + interest;
      const nextInterest = totalInterestCents + interest;
      if (!Number.isSafeInteger(interest) || !Number.isSafeInteger(nextBalance) || !Number.isSafeInteger(nextInterest)) {
        return null;
      }
      state.balanceCents = nextBalance;
      totalInterestCents = nextInterest;
    }
    let available = monthlyBudgetCents;
    const beforePayment = new Map(states.map((state) => [state.debt.id, state.balanceCents]));
    for (const state of states) {
      if (state.balanceCents <= 0) continue;
      const payment = Math.min(state.debt.minimumPaymentCents, state.balanceCents, available);
      state.balanceCents -= payment;
      available -= payment;
    }
    for (const state of states) {
      if (state.balanceCents <= 0 || available <= 0) continue;
      const payment = Math.min(state.balanceCents, available);
      state.balanceCents -= payment;
      available -= payment;
    }
    for (const state of states) {
      if ((beforePayment.get(state.debt.id) ?? 0) > 0 && state.balanceCents <= 0) {
        payoffEvents.push({ debtId: state.debt.id, debtName: state.debt.name, month });
      }
    }
    const remainingBalanceCents = states.reduce((sum, state) => sum + Math.max(0, state.balanceCents), 0);
    if (!Number.isSafeInteger(remainingBalanceCents)) return null;
    const target = states.find((state) => state.balanceCents > 0)?.debt ?? null;
    trajectory.push({
      month,
      remainingBalanceCents,
      cumulativeInterestCents: totalInterestCents,
      targetDebtId: target?.id ?? null,
      targetDebtName: target?.name ?? null,
    });
    const repaid = originalBalanceCents <= 0
      ? 100
      : Math.floor(((originalBalanceCents - remainingBalanceCents) * 100) / originalBalanceCents);
    for (const percentage of [25, 50, 75, 100] as const) {
      if (repaid >= percentage && !reachedMilestones.has(percentage)) {
        reachedMilestones.add(percentage);
        milestones.push({ percentage, month, remainingBalanceCents });
      }
    }
    if (states.every((state) => state.balanceCents <= 0)) {
      return {
        payoffMonths: month,
        totalInterestCents,
        monthlyPaymentCents: monthlyBudgetCents,
        trajectory,
        payoffEvents,
        milestones,
      };
    }
  }
  return null;
}

export function strategyComparisons(
  debts: Debt[],
  monthlyBudgetCents: number,
  preferences: DebtPreferences,
): DebtStrategyComparison[] {
  const active = debts.filter((debt) => debt.isActive !== false && debt.balanceCents > 0);
  const avalanche = [...active].sort(
    (left, right) => right.annualInterestBps - left.annualInterestBps || left.balanceCents - right.balanceCents,
  );
  const snowball = [...active].sort(
    (left, right) => left.balanceCents - right.balanceCents || right.annualInterestBps - left.annualInterestBps,
  );
  const comparisons: DebtStrategyComparison[] = [
    { strategy: "avalanche", projection: simulateDebtPayoff(avalanche, monthlyBudgetCents) },
    { strategy: "snowball", projection: simulateDebtPayoff(snowball, monthlyBudgetCents) },
    { strategy: "hybrid", projection: simulateDebtPayoff(hybridDebtOrder(active), monthlyBudgetCents) },
  ];
  const consolidation = consolidationProjection(active.reduce((sum, debt) => sum + debt.balanceCents, 0), preferences);
  if (consolidation !== null) comparisons.push({ strategy: "consolidation_review", projection: consolidation });
  return comparisons;
}

function hybridDebtOrder(debts: Debt[]): Debt[] {
  const smallest = [...debts].sort((left, right) => left.balanceCents - right.balanceCents)[0]!;
  return [
    smallest,
    ...debts
      .filter((debt) => debt.id !== smallest.id)
      .sort((left, right) => right.annualInterestBps - left.annualInterestBps || left.balanceCents - right.balanceCents),
  ];
}

function consolidationMonthlyPayment(totalBalanceCents: number, preferences: DebtPreferences): number | null {
  const apr = preferences.consolidationAprBps;
  const months = preferences.consolidationTermMonths;
  if (apr === null || months === null) return null;
  const monthlyRate = apr / 120_000;
  const payment = monthlyRate === 0
    ? totalBalanceCents / months
    : totalBalanceCents * monthlyRate / (1 - (1 + monthlyRate) ** -months);
  const rounded = Math.round(payment);
  return Number.isSafeInteger(rounded) ? rounded : null;
}

function consolidationProjection(totalBalanceCents: number, preferences: DebtPreferences): DebtProjection | null {
  const months = preferences.consolidationTermMonths;
  const payment = consolidationMonthlyPayment(totalBalanceCents, preferences);
  if (months === null || payment === null) return null;
  const totalCost = payment * months + preferences.consolidationFeesCents;
  if (!Number.isSafeInteger(totalCost)) return null;
  return {
    payoffMonths: months,
    totalInterestCents: Math.max(0, totalCost - totalBalanceCents),
    monthlyPaymentCents: payment,
    trajectory: Array.from({ length: months + 1 }, (_, month) => ({
      month,
      remainingBalanceCents: Math.max(0, Math.round(totalBalanceCents * (1 - month / months))),
      cumulativeInterestCents: Math.max(0, Math.round((totalCost - totalBalanceCents) * (month / months))),
      targetDebtId: null,
      targetDebtName: "Consolidation loan",
    })),
    payoffEvents: [{ debtId: "consolidation", debtName: "Consolidation loan", month: months }],
    milestones: ([25, 50, 75, 100] as const).map((percentage) => ({
      percentage,
      month: Math.max(1, Math.ceil(months * percentage / 100)),
      remainingBalanceCents: Math.max(0, Math.round(totalBalanceCents * (1 - percentage / 100))),
    })),
  };
}

export { demoDashboard } from "./demo-data";
