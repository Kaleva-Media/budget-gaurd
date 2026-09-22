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
): BudgetSummary {
  const limitCents = budgets.reduce((sum, budget) => sum + budget.limitCents, 0);
  const spentCents = budgets.reduce((sum, budget) => sum + budget.spentCents, 0);
  const committedCents = budgets.reduce(
    (sum, budget) => sum + budget.committedCents,
    0,
  );
  const effectiveSpend = spentCents + committedCents;
  const remainingCents = Math.max(0, limitCents - effectiveSpend);
  const lastDay = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
  const daysRemaining = Math.max(1, lastDay - now.getDate() + 1);

  return {
    limitCents,
    spentCents,
    committedCents,
    remainingCents,
    safeToSpendTodayCents: Math.floor(remainingCents / daysRemaining),
    daysRemaining,
  };
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
  /** R — remaining planned outflows (after subtracting matched posted + pending) */
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

  const matchedCentsByItem = new Map<string, number>();
  for (const tx of transactions) {
    if (tx.status === "posted" || tx.status === "pending") {
      for (const plannedId of tx.plannedItemIds) {
        const current = matchedCentsByItem.get(plannedId) ?? 0;
        matchedCentsByItem.set(plannedId, current + Math.abs(tx.amountCents));
      }
    }
  }

  const rCents = plannedItems
    .filter((item) => item.direction === "expense")
    .reduce((sum, item) => {
      const matched = item.manuallyPaid
        ? item.plannedCents
        : Math.max(item.actualCents, matchedCentsByItem.get(item.id) ?? 0);
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

export { demoDashboard } from "./demo-data";
