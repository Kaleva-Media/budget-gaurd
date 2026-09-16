import {
  demoDashboard,
  type DashboardData,
  type PlannedItem,
} from "@budget-guard/domain";
import { isSupabaseConfigured, supabase } from "./supabase";

interface AccountRow {
  id: string;
  name: string;
  type: DashboardData["accounts"][number]["type"];
  role: DashboardData["accounts"][number]["role"];
  purpose: string;
  mask: string;
  current_balance_cents: number;
  credit_limit_cents: number | null;
  include_in_safe_to_spend: boolean;
  display_order: number;
}

interface BudgetPeriodRow {
  id: string;
  starts_on: string;
  status: DashboardData["period"]["status"];
  carryover_cents: number;
}

interface CategoryRow {
  id: string;
  name: string;
  colour: string;
  icon: string;
}

interface BudgetRow {
  id: string;
  category_id: string;
  limit_cents: number;
  spent_cents: number;
  committed_cents: number;
}

interface TransactionRow {
  id: string;
  account_id: string;
  category_id: string | null;
  occurred_on: string;
  occurred_at: string | null;
  amount_cents: number;
  status: "pending" | "posted" | "reversed" | "failed";
  kind: DashboardData["transactions"][number]["kind"];
  source: DashboardData["transactions"][number]["source"];
  merchant: string | null;
  description: string | null;
  needs_review: boolean;
  planned_item_matches: { planned_item_id: string }[] | null;
}

interface PlannedItemRow {
  id: string;
  direction: PlannedItem["direction"];
  kind: PlannedItem["kind"];
  name: string;
  planned_cents: number;
  actual_cents: number;
  account_id: string | null;
  category_id: string | null;
  due_day: number | null;
  sort_order: number;
}

export async function loadDashboard(): Promise<DashboardData> {
  if (!isSupabaseConfigured || !supabase) return demoDashboard;

  const entityResult = await supabase
    .from("entities")
    .select("id")
    .eq("is_default", true)
    .single();
  if (entityResult.error) throw entityResult.error;
  const entityId = entityResult.data.id as string;

  const start = new Date();
  start.setDate(1);
  start.setHours(0, 0, 0, 0);
  const periodStart = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, "0")}-01`;

  let periodResult = await supabase
    .from("budget_periods")
    .select("id,starts_on,status,carryover_cents")
    .eq("entity_id", entityId)
    .eq("starts_on", periodStart)
    .maybeSingle();

  if (periodResult.error) throw periodResult.error;
  if (!periodResult.data) {
    const userResult = await supabase.auth.getUser();
    if (userResult.error || !userResult.data.user) {
      throw userResult.error ?? new Error("Sign in again to create this month's plan.");
    }
    periodResult = await supabase
      .from("budget_periods")
      .insert({ user_id: userResult.data.user.id, entity_id: entityId, starts_on: periodStart, status: "active" })
      .select("id,starts_on,status,carryover_cents")
      .single();
    if (periodResult.error) throw periodResult.error;
  }

  const period = periodResult.data as BudgetPeriodRow;
  const [accountsResult, categoriesResult, budgetsResult, plannedItemsResult, transactionsResult] =
    await Promise.all([
      supabase
        .from("accounts")
        .select("id,name,type,role,purpose,mask,current_balance_cents,credit_limit_cents,include_in_safe_to_spend,display_order")
        .eq("entity_id", entityId)
        .eq("is_active", true)
        .order("display_order"),
      supabase.from("categories").select("id,name,colour,icon").order("sort_order"),
      supabase
        .from("budget_progress")
        .select("id,category_id,limit_cents,spent_cents,committed_cents")
        .eq("entity_id", entityId)
        .eq("period_start", periodStart),
      supabase
        .from("planned_item_progress")
        .select("id,direction,kind,name,planned_cents,actual_cents,account_id,category_id,due_day,sort_order")
        .eq("budget_period_id", period.id)
        .order("direction", { ascending: false })
        .order("sort_order"),
      supabase
        .from("transactions")
        .select("id,account_id,category_id,occurred_on,occurred_at,amount_cents,status,kind,source,merchant,description,needs_review,planned_item_matches(planned_item_id)")
        .eq("entity_id", entityId)
        .gte("occurred_on", periodStart)
        .order("occurred_on", { ascending: false })
        .order("occurred_at", { ascending: false, nullsFirst: false })
        .limit(100),
    ]);

  const error =
    accountsResult.error ??
    categoriesResult.error ??
    budgetsResult.error ??
    plannedItemsResult.error ??
    transactionsResult.error;
  if (error) throw error;

  const accounts = (accountsResult.data ?? []) as AccountRow[];
  const categories = (categoriesResult.data ?? []) as CategoryRow[];
  const budgets = (budgetsResult.data ?? []) as BudgetRow[];
  const plannedItems = (plannedItemsResult.data ?? []) as PlannedItemRow[];
  const transactions = (transactionsResult.data ?? []) as TransactionRow[];

  return {
    month: new Intl.DateTimeFormat("en-ZA", { month: "long", year: "numeric" }).format(new Date()),
    period: {
      id: period.id,
      startsOn: period.starts_on,
      status: period.status,
      carryoverCents: period.carryover_cents,
    },
    accounts: accounts.map((row) => ({
      id: row.id,
      name: row.name,
      type: row.type,
      role: row.role,
      purpose: row.purpose,
      mask: row.mask,
      currentBalanceCents: row.current_balance_cents,
      creditLimitCents: row.credit_limit_cents,
      includeInSafeToSpend: row.include_in_safe_to_spend,
      displayOrder: row.display_order,
    })),
    categories,
    budgets: budgets.map((row) => ({
      id: row.id,
      categoryId: row.category_id,
      limitCents: row.limit_cents,
      spentCents: row.spent_cents,
      committedCents: row.committed_cents,
    })),
    plannedItems: plannedItems.map((row) => ({
      id: row.id,
      direction: row.direction,
      kind: row.kind,
      name: row.name,
      plannedCents: row.planned_cents,
      actualCents: row.actual_cents,
      accountId: row.account_id,
      categoryId: row.category_id,
      dueDay: row.due_day,
      sortOrder: row.sort_order,
    })),
    transactions: transactions.map((row) => ({
      id: row.id,
      accountId: row.account_id,
      categoryId: row.category_id,
      occurredAt: row.occurred_at ?? `${row.occurred_on}T12:00:00+02:00`,
      amountCents: row.amount_cents,
      status: row.status,
      kind: row.kind,
      source: row.source,
      merchant: row.merchant ?? "Unknown transaction",
      description: row.description ?? "",
      needsReview: row.needs_review,
      plannedItemIds: (row.planned_item_matches ?? []).map((match) => match.planned_item_id),
    })),
  };
}

export type NewPlannedItem = Omit<PlannedItem, "id" | "actualCents" | "sortOrder">;

export async function createPlannedItem(
  periodId: string,
  item: NewPlannedItem,
): Promise<string> {
  if (!isSupabaseConfigured || !supabase) return crypto.randomUUID();
  const userResult = await supabase.auth.getUser();
  if (userResult.error || !userResult.data.user) {
    throw userResult.error ?? new Error("Sign in again to update your plan.");
  }
  const periodResult = await supabase
    .from("budget_periods")
    .select("entity_id")
    .eq("id", periodId)
    .single();
  if (periodResult.error) throw periodResult.error;
  const { data, error } = await supabase
    .from("planned_items")
    .insert({
      user_id: userResult.data.user.id,
      entity_id: periodResult.data.entity_id,
      budget_period_id: periodId,
      direction: item.direction,
      kind: item.kind,
      name: item.name,
      planned_cents: item.plannedCents,
      account_id: item.accountId,
      category_id: item.categoryId,
      due_day: item.dueDay,
    })
    .select("id")
    .single();
  if (error) throw error;
  return data.id as string;
}

export async function updateTransactionCategory(
  transactionId: string,
  categoryId: string | null,
  needsReview = false,
) {
  if (!isSupabaseConfigured || !supabase) return;
  const { error } = await supabase
    .from("transactions")
    .update({ category_id: categoryId, needs_review: needsReview })
    .eq("id", transactionId);
  if (error) throw error;
}
