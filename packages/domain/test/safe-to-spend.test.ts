import { describe, expect, test } from "bun:test";
import {
  summariseSafeToSpend,
  type Account,
  type PlannedItem,
  type Transaction,
} from "../src";

describe("safe to spend (Home)", () => {
  const stsAccount: Account = {
    id: "cheque",
    name: "FNB Cheque",
    type: "cheque",
    role: "operational",
    purpose: "Daily banking",
    mask: "1234",
    currentBalanceCents: 500_000,
    creditLimitCents: null,
    includeInSafeToSpend: true,
    displayOrder: 1,
  };

  const nonStsAccount: Account = {
    id: "savings",
    name: "Discovery Savings",
    type: "savings",
    role: "savings",
    purpose: "Emergency fund",
    mask: "5678",
    currentBalanceCents: 200_000,
    creditLimitCents: null,
    includeInSafeToSpend: false,
    displayOrder: 2,
  };

  const plannedExpense: PlannedItem = {
    id: "rent",
    direction: "expense",
    kind: "fixed_expense",
    name: "Rent",
    plannedCents: 150_000,
    actualCents: 0,
    accountId: "cheque",
    categoryId: "housing",
    dueDay: 1,
    sortOrder: 1,
  };

  const plannedIncome: PlannedItem = {
    id: "salary",
    direction: "income",
    kind: "income",
    name: "Salary",
    plannedCents: 300_000,
    actualCents: 0,
    accountId: "cheque",
    categoryId: null,
    dueDay: 25,
    sortOrder: 0,
  };

  test("STS = B − C with no commitments", () => {
    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [],
      transactions: [],
    });

    expect(result.bCents).toBe(500_000);
    expect(result.rCents).toBe(0);
    expect(result.pCents).toBe(0);
    expect(result.cCents).toBe(0);
    expect(result.safeToSpendCents).toBe(500_000);
  });

  test("B sums only STS-enabled accounts", () => {
    const result = summariseSafeToSpend({
      accounts: [stsAccount, nonStsAccount],
      plannedItems: [],
      transactions: [],
    });

    expect(result.bCents).toBe(500_000);
    expect(result.safeToSpendCents).toBe(500_000);
  });

  test("R includes unmatched planned expense", () => {
    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedExpense],
      transactions: [],
    });

    expect(result.rCents).toBe(150_000);
    expect(result.pCents).toBe(0);
    expect(result.cCents).toBe(150_000);
    expect(result.safeToSpendCents).toBe(350_000);
  });

  test("planned income does not affect STS", () => {
    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedIncome, plannedExpense],
      transactions: [],
    });

    expect(result.rCents).toBe(150_000);
    expect(result.safeToSpendCents).toBe(350_000);
  });

  test("P includes all pending outflows on STS accounts", () => {
    const pendingTx: Transaction = {
      id: "pending1",
      accountId: "cheque",
      categoryId: null,
      occurredAt: "2026-09-17T10:00:00+02:00",
      amountCents: -50_000,
      status: "pending",
      kind: "card_purchase",
      source: "sms",
      merchant: "Woolworths",
      description: "Card pending",
      needsReview: false,
      plannedItemIds: [],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [],
      transactions: [pendingTx],
    });

    expect(result.pCents).toBe(50_000);
    expect(result.cCents).toBe(50_000);
    expect(result.safeToSpendCents).toBe(450_000);
  });

  test("P excludes pending on non-STS accounts", () => {
    const pendingTx: Transaction = {
      id: "pending1",
      accountId: "savings",
      categoryId: null,
      occurredAt: "2026-09-17T10:00:00+02:00",
      amountCents: -50_000,
      status: "pending",
      kind: "card_purchase",
      source: "sms",
      merchant: "Woolworths",
      description: "Card pending",
      needsReview: false,
      plannedItemIds: [],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount, nonStsAccount],
      plannedItems: [],
      transactions: [pendingTx],
    });

    expect(result.pCents).toBe(0);
    expect(result.safeToSpendCents).toBe(500_000);
  });

  test("P excludes transfers and reversals", () => {
    const transfer: Transaction = {
      id: "transfer1",
      accountId: "cheque",
      categoryId: null,
      occurredAt: "2026-09-17T10:00:00+02:00",
      amountCents: -50_000,
      status: "pending",
      kind: "transfer",
      source: "sms",
      merchant: "",
      description: "Transfer",
      needsReview: false,
      plannedItemIds: [],
    };

    const reversal: Transaction = {
      id: "reversal1",
      accountId: "cheque",
      categoryId: null,
      occurredAt: "2026-09-17T10:00:00+02:00",
      amountCents: -50_000,
      status: "pending",
      kind: "reversal",
      source: "sms",
      merchant: "",
      description: "Reversal",
      needsReview: false,
      plannedItemIds: [],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [],
      transactions: [transfer, reversal],
    });

    expect(result.pCents).toBe(0);
    expect(result.safeToSpendCents).toBe(500_000);
  });

  test("posted match reduces R but not P", () => {
    const postedTx: Transaction = {
      id: "posted1",
      accountId: "cheque",
      categoryId: "housing",
      occurredAt: "2026-09-01T10:00:00+02:00",
      amountCents: -150_000,
      status: "posted",
      kind: "scheduled_payment",
      source: "sms",
      merchant: "Property Manager",
      description: "Rent payment",
      needsReview: false,
      plannedItemIds: ["rent"],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedExpense],
      transactions: [postedTx],
    });

    expect(result.rCents).toBe(0);
    expect(result.pCents).toBe(0);
    expect(result.cCents).toBe(0);
    expect(result.safeToSpendCents).toBe(500_000);
  });

  test("pending matched to plan moves R→P, C unchanged (Neo dedup)", () => {
    const beforeWithoutPending = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedExpense],
      transactions: [],
    });

    expect(beforeWithoutPending.rCents).toBe(150_000);
    expect(beforeWithoutPending.pCents).toBe(0);
    expect(beforeWithoutPending.cCents).toBe(150_000);

    const matchedPending: Transaction = {
      id: "pending1",
      accountId: "cheque",
      categoryId: "housing",
      occurredAt: "2026-09-17T10:00:00+02:00",
      amountCents: -150_000,
      status: "pending",
      kind: "scheduled_payment",
      source: "sms",
      merchant: "Property Manager",
      description: "Rent pending",
      needsReview: false,
      plannedItemIds: ["rent"],
    };

    const afterMatchedPending = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedExpense],
      transactions: [matchedPending],
    });

    expect(afterMatchedPending.rCents).toBe(0);
    expect(afterMatchedPending.pCents).toBe(150_000);
    expect(afterMatchedPending.cCents).toBe(150_000);
    expect(afterMatchedPending.safeToSpendCents).toBe(350_000);

    expect(afterMatchedPending.cCents).toBe(beforeWithoutPending.cCents);
    expect(afterMatchedPending.safeToSpendCents).toBe(beforeWithoutPending.safeToSpendCents);
  });

  test("partial match reduces R proportionally", () => {
    const partialTx: Transaction = {
      id: "posted1",
      accountId: "cheque",
      categoryId: "housing",
      occurredAt: "2026-09-01T10:00:00+02:00",
      amountCents: -100_000,
      status: "posted",
      kind: "scheduled_payment",
      source: "sms",
      merchant: "Property Manager",
      description: "Partial rent",
      needsReview: false,
      plannedItemIds: ["rent"],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedExpense],
      transactions: [partialTx],
    });

    expect(result.rCents).toBe(50_000);
    expect(result.pCents).toBe(0);
    expect(result.cCents).toBe(50_000);
    expect(result.safeToSpendCents).toBe(450_000);
  });

  test("C = R + P with mixed commitments", () => {
    const groceries: PlannedItem = {
      id: "groceries",
      direction: "expense",
      kind: "variable_expense",
      name: "Groceries",
      plannedCents: 100_000,
      actualCents: 0,
      accountId: "cheque",
      categoryId: "food",
      dueDay: null,
      sortOrder: 2,
    };

    const pendingTx: Transaction = {
      id: "pending1",
      accountId: "cheque",
      categoryId: null,
      occurredAt: "2026-09-17T10:00:00+02:00",
      amountCents: -30_000,
      status: "pending",
      kind: "card_purchase",
      source: "sms",
      merchant: "Woolworths",
      description: "Card pending",
      needsReview: false,
      plannedItemIds: [],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedExpense, groceries],
      transactions: [pendingTx],
    });

    expect(result.rCents).toBe(250_000);
    expect(result.pCents).toBe(30_000);
    expect(result.cCents).toBe(280_000);
    expect(result.safeToSpendCents).toBe(220_000);
  });

  test("STS may be negative (no clamp at 0)", () => {
    const highPlanned: PlannedItem = {
      id: "rent",
      direction: "expense",
      kind: "fixed_expense",
      name: "Rent",
      plannedCents: 600_000,
      actualCents: 0,
      accountId: "cheque",
      categoryId: "housing",
      dueDay: 1,
      sortOrder: 1,
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [highPlanned],
      transactions: [],
    });

    expect(result.bCents).toBe(500_000);
    expect(result.cCents).toBe(600_000);
    expect(result.safeToSpendCents).toBe(-100_000);
  });

  test("multiple planned items with various match states", () => {
    const rent: PlannedItem = {
      id: "rent",
      direction: "expense",
      kind: "fixed_expense",
      name: "Rent",
      plannedCents: 150_000,
      actualCents: 150_000,
      accountId: "cheque",
      categoryId: "housing",
      dueDay: 1,
      sortOrder: 1,
    };

    const groceries: PlannedItem = {
      id: "groceries",
      direction: "expense",
      kind: "variable_expense",
      name: "Groceries",
      plannedCents: 100_000,
      actualCents: 0,
      accountId: "cheque",
      categoryId: "food",
      dueDay: null,
      sortOrder: 2,
    };

    const insurance: PlannedItem = {
      id: "insurance",
      direction: "expense",
      kind: "fixed_expense",
      name: "Insurance",
      plannedCents: 80_000,
      actualCents: 0,
      accountId: "cheque",
      categoryId: "insurance",
      dueDay: 15,
      sortOrder: 3,
    };

    const rentPosted: Transaction = {
      id: "rent_tx",
      accountId: "cheque",
      categoryId: "housing",
      occurredAt: "2026-09-01T10:00:00+02:00",
      amountCents: -150_000,
      status: "posted",
      kind: "scheduled_payment",
      source: "sms",
      merchant: "Property Manager",
      description: "Rent",
      needsReview: false,
      plannedItemIds: ["rent"],
    };

    const insurancePending: Transaction = {
      id: "insurance_tx",
      accountId: "cheque",
      categoryId: "insurance",
      occurredAt: "2026-09-15T10:00:00+02:00",
      amountCents: -80_000,
      status: "pending",
      kind: "scheduled_payment",
      source: "sms",
      merchant: "Discovery",
      description: "Insurance",
      needsReview: false,
      plannedItemIds: ["insurance"],
    };

    const unmatchedPending: Transaction = {
      id: "pending1",
      accountId: "cheque",
      categoryId: null,
      occurredAt: "2026-09-17T10:00:00+02:00",
      amountCents: -25_000,
      status: "pending",
      kind: "card_purchase",
      source: "sms",
      merchant: "Woolworths",
      description: "Card pending",
      needsReview: false,
      plannedItemIds: [],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [rent, groceries, insurance],
      transactions: [rentPosted, insurancePending, unmatchedPending],
    });

    expect(result.rCents).toBe(100_000);
    expect(result.pCents).toBe(105_000);
    expect(result.cCents).toBe(205_000);
    expect(result.safeToSpendCents).toBe(295_000);
  });

  test("savings and debt payment kinds count as planned outflows", () => {
    const savings: PlannedItem = {
      id: "emergency",
      direction: "expense",
      kind: "savings",
      name: "Emergency fund",
      plannedCents: 50_000,
      actualCents: 0,
      accountId: "cheque",
      categoryId: null,
      dueDay: 1,
      sortOrder: 4,
    };

    const debt: PlannedItem = {
      id: "card_debt",
      direction: "expense",
      kind: "debt_payment",
      name: "Credit card",
      plannedCents: 30_000,
      actualCents: 0,
      accountId: "cheque",
      categoryId: null,
      dueDay: 10,
      sortOrder: 5,
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [savings, debt],
      transactions: [],
    });

    expect(result.rCents).toBe(80_000);
    expect(result.cCents).toBe(80_000);
    expect(result.safeToSpendCents).toBe(420_000);
  });

  test("over-matched planned item contributes 0 to R", () => {
    const overMatchedTx: Transaction = {
      id: "posted1",
      accountId: "cheque",
      categoryId: "housing",
      occurredAt: "2026-09-01T10:00:00+02:00",
      amountCents: -200_000,
      status: "posted",
      kind: "scheduled_payment",
      source: "sms",
      merchant: "Property Manager",
      description: "Rent overpayment",
      needsReview: false,
      plannedItemIds: ["rent"],
    };

    const result = summariseSafeToSpend({
      accounts: [stsAccount],
      plannedItems: [plannedExpense],
      transactions: [overMatchedTx],
    });

    expect(result.rCents).toBe(0);
    expect(result.safeToSpendCents).toBe(500_000);
  });
});
