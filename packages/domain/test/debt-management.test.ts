import { describe, expect, test } from "bun:test";
import {
  recommendDebtStrategy,
  simulateDebtPayoff,
  type Debt,
  type DebtPlanInput,
  type PlannedItem,
} from "../src";

const card: Debt = {
  id: "card",
  name: "Credit card",
  type: "credit_card",
  balanceCents: 1_000_000,
  annualInterestBps: 2_400,
  minimumPaymentCents: 50_000,
  remainingTermMonths: null,
  dueDay: 20,
  secured: false,
  inArrears: false,
};
const loan: Debt = {
  ...card,
  id: "loan",
  name: "Loan",
  type: "personal_loan",
  balanceCents: 2_000_000,
  annualInterestBps: 1_200,
  minimumPaymentCents: 80_000,
};

function input(overrides: Partial<DebtPlanInput> = {}): DebtPlanInput {
  const items: PlannedItem[] = [
    { id: "salary", direction: "income", kind: "income", name: "Salary", plannedCents: 1_200_000, actualCents: 0, accountId: null, categoryId: null, dueDay: 25, sortOrder: 1 },
    { id: "rent", direction: "expense", kind: "fixed_expense", name: "Rent", plannedCents: 700_000, actualCents: 0, accountId: null, categoryId: null, dueDay: 1, sortOrder: 2 },
    { id: "living", direction: "expense", kind: "variable_expense", name: "Living", plannedCents: 200_000, actualCents: 0, accountId: null, categoryId: null, dueDay: null, sortOrder: 3 },
  ];
  return {
    debts: [loan, card],
    preferences: { goal: "lowest_cost", consolidationAprBps: null, consolidationTermMonths: null, consolidationFeesCents: 0 },
    plannedItems: items,
    budgets: [],
    ...overrides,
  };
}

describe("debt strategy recommendation", () => {
  test("recommends avalanche for lowest total interest", () => {
    const plan = recommendDebtStrategy(input())!;
    expect(plan.strategy).toBe("avalanche");
    expect(plan.payoffOrder.map((debt) => debt.id)).toEqual(["card", "loan"]);
    expect(plan.extraPaymentCents).toBe(170_000);
    expect(plan.projection?.payoffMonths).toBeLessThan(24);
  });

  test("routes arrears and unaffordable minimums to formal support", () => {
    expect(recommendDebtStrategy(input({ debts: [{ ...card, inArrears: true }] }))?.strategy).toBe("formal_support");
    expect(recommendDebtStrategy(input({ debts: [{ ...card, minimumPaymentCents: 350_000 }] }))?.strategy).toBe("formal_support");
  });

  test("uses a category limit instead of double counting its smaller planned expense", () => {
    const base = input();
    const food: PlannedItem = { ...base.plannedItems[1]!, id: "food", name: "Food", plannedCents: 100_000, categoryId: "groceries" };
    const plan = recommendDebtStrategy(input({
      debts: [card],
      plannedItems: [...base.plannedItems, food],
      budgets: [{ id: "food-limit", categoryId: "groceries", limitCents: 250_000, spentCents: 0, committedCents: 0 }],
    }))!;
    expect(plan.monthlyLivingPlanCents).toBe(1_150_000);
  });

  test("recommends reviewing a materially cheaper affordable consolidation offer", () => {
    const base = input();
    const plan = recommendDebtStrategy(input({
      plannedItems: base.plannedItems.map((item) => item.direction === "income" ? { ...item, plannedCents: 1_150_000 } : item),
      preferences: { goal: "lowest_cost", consolidationAprBps: 0, consolidationTermMonths: 12, consolidationFeesCents: 0 },
    }))!;
    expect(plan.strategy).toBe("consolidation_review");
  });

  test("does not simulate below combined minimums", () => {
    expect(simulateDebtPayoff([card, loan], 100_000)).toBeNull();
  });

  test("converts annual basis points to monthly interest", () => {
    expect(simulateDebtPayoff([{ ...card, annualInterestBps: 1_200, minimumPaymentCents: 1 }], 1_010_000)).toEqual({
      payoffMonths: 1,
      totalInterestCents: 10_000,
    });
  });

  test("refuses a projection that would exceed safe integer cents", () => {
    expect(simulateDebtPayoff([{
      ...card,
      balanceCents: Number.MAX_SAFE_INTEGER / 2,
      annualInterestBps: 100_000,
      minimumPaymentCents: 1,
    }], 1)).toBeNull();
  });
});
