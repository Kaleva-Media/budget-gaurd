import { describe, expect, test } from "bun:test";
import {
  budgetPercentage,
  isUnplannedPayment,
  plannedItemStatus,
  summariseBudgets,
  summariseCashflow,
  type Budget,
  type PlannedItem,
  type Transaction,
} from "../src";

const budgets: Budget[] = [
  { id: "one", categoryId: "food", limitCents: 100_000, spentCents: 30_000, committedCents: 5_000 },
  { id: "two", categoryId: "travel", limitCents: 50_000, spentCents: 20_000, committedCents: 0 },
];

describe("budget calculations", () => {
  test("keeps pending reservations separate but committed", () => {
    const result = summariseBudgets(budgets, new Date(2026, 8, 21));

    expect(result.limitCents).toBe(150_000);
    expect(result.spentCents).toBe(50_000);
    expect(result.committedCents).toBe(5_000);
    expect(result.remainingCents).toBe(95_000);
    expect(result.safeToSpendTodayCents).toBe(9_500);
  });

  test("caps visual budget utilisation at 100 percent", () => {
    expect(budgetPercentage({ ...budgets[0]!, spentCents: 120_000 })).toBe(100);
  });
});

describe("unplanned payment triage", () => {
  const payment: Transaction = {
    id: "payment",
    accountId: "card",
    categoryId: null,
    occurredAt: "2026-09-11T10:00:00+02:00",
    amountCents: -12_500,
    status: "posted",
    kind: "card_purchase",
    source: "sms",
    merchant: "Example merchant",
    description: "Card purchase",
    needsReview: true,
    plannedItemIds: [],
  };

  test("queues only uncategorised outgoing payments with no plan match", () => {
    expect(isUnplannedPayment(payment)).toBe(true);
    expect(isUnplannedPayment({ ...payment, categoryId: "food" })).toBe(false);
    expect(isUnplannedPayment({ ...payment, plannedItemIds: ["planned"] })).toBe(false);
    expect(isUnplannedPayment({ ...payment, kind: "transfer" })).toBe(false);
    expect(isUnplannedPayment({ ...payment, amountCents: 12_500 })).toBe(false);
  });
});

describe("monthly cash-flow plan", () => {
  const items: PlannedItem[] = [
    { id: "salary", direction: "income", kind: "income", name: "Salary", plannedCents: 500_000, actualCents: 500_000, accountId: "cheque", categoryId: null, dueDay: 25, sortOrder: 1 },
    { id: "rent", direction: "expense", kind: "fixed_expense", name: "Housing", plannedCents: 200_000, actualCents: 200_000, accountId: "cheque", categoryId: "home", dueDay: 1, sortOrder: 1 },
    { id: "food", direction: "expense", kind: "variable_expense", name: "Food", plannedCents: 100_000, actualCents: 40_000, accountId: "card", categoryId: "food", dueDay: null, sortOrder: 2 },
  ];

  test("projects surplus from carryover, expected income and known expenses", () => {
    const result = summariseCashflow(
      { id: "period", startsOn: "2026-09-01", status: "active", carryoverCents: 50_000 },
      items,
    );

    expect(result.plannedIncomeCents).toBe(500_000);
    expect(result.plannedExpenseCents).toBe(300_000);
    expect(result.projectedSurplusCents).toBe(250_000);
    expect(result.remainingToFundCents).toBe(60_000);
    expect(result.allocationPercentage).toBe(55);
  });

  test("derives progress without storing a conflicting status", () => {
    expect(plannedItemStatus(items[0]!)).toBe("settled");
    expect(plannedItemStatus(items[2]!)).toBe("partial");
    expect(plannedItemStatus({ ...items[2]!, actualCents: 0 })).toBe("expected");
  });
});
