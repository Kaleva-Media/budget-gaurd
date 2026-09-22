import {
  ArrowDownLeft, ArrowUpRight, Bell, CalendarRange, Check, CheckCircle2,
  ChevronDown, CircleAlert, CreditCard, House, Landmark, LayoutDashboard,
  GripVertical, Inbox, Menu, MoreHorizontal, PiggyBank, Plus, RefreshCw,
  Search, Settings, ShieldCheck, Smartphone, Sparkles, Undo2, WalletCards, X,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import {
  budgetPercentage, formatZar, groupPlannedItems, isUnplannedPayment, plannedItemStatus, summariseBudgets,
  summariseCashflow, type Account, type DashboardData, type PlannedItem,
  type ExpenseOrder, type PlannedItemDirection, type Transaction,
} from "@budget-guard/domain";
import { createPlannedItem, loadDashboard, setPlannedExpensePaid, updateTransactionCategory } from "./lib/dashboard";
import { isSupabaseConfigured, supabase } from "./lib/supabase";

type View = "overview" | "plan" | "transactions" | "accounts";

const navItems: { id: View; label: string; icon: typeof LayoutDashboard }[] = [
  { id: "overview", label: "Overview", icon: LayoutDashboard },
  { id: "plan", label: "Monthly plan", icon: CalendarRange },
  { id: "transactions", label: "Transactions", icon: WalletCards },
  { id: "accounts", label: "Accounts", icon: Landmark },
];

const pageTitles: Record<View, string> = {
  overview: "Your money, today.",
  plan: "Build the month before it happens.",
  transactions: "Every movement.",
  accounts: "Every account has a job.",
};

function useAuthState() {
  const [loading, setLoading] = useState(isSupabaseConfigured);
  const [user, setUser] = useState<{ id: string; email?: string } | null>(
    isSupabaseConfigured ? null : { id: "demo", email: "demo@budgetguard.local" },
  );
  const [recoveringPassword, setRecoveringPassword] = useState(false);
  useEffect(() => {
    if (!supabase) return;
    void supabase.auth.getSession().then(({ data }) => {
      setUser(data.session?.user ?? null);
      setLoading(false);
    });
    const { data } = supabase.auth.onAuthStateChange((event, session) => {
      setUser(session?.user ?? null);
      setRecoveringPassword(event === "PASSWORD_RECOVERY");
      setLoading(false);
    });
    return () => data.subscription.unsubscribe();
  }, []);
  return { loading, user, recoveringPassword, finishRecovery: () => setRecoveringPassword(false) };
}

type AuthMode = "sign-in" | "sign-up" | "forgot";

function SignIn() {
  const [mode, setMode] = useState<AuthMode>("sign-in");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  function changeMode(next: AuthMode) {
    setMode(next);
    setError("");
    setNotice("");
    setPassword("");
    setConfirmPassword("");
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!supabase) return;
    setSubmitting(true);
    setError("");
    setNotice("");

    if (mode === "forgot") {
      const { error: resetError } = await supabase.auth.resetPasswordForEmail(email, {
        redirectTo: window.location.origin,
      });
      if (resetError) setError(resetError.message);
      else setNotice(`A password reset link was sent to ${email}.`);
      setSubmitting(false);
      return;
    }

    if (mode === "sign-up" && password !== confirmPassword) {
      setError("The passwords do not match.");
      setSubmitting(false);
      return;
    }

    const result = mode === "sign-up"
      ? await supabase.auth.signUp({
          email,
          password,
          options: { emailRedirectTo: window.location.origin },
        })
      : await supabase.auth.signInWithPassword({ email, password });

    if (result.error) setError(result.error.message);
    else if (mode === "sign-up" && !result.data.session) {
      setNotice(`Check ${email} to confirm your account.`);
    }
    setSubmitting(false);
  }

  return (
    <main className="auth-shell">
      <section className="auth-story">
        <div className="brand brand--large"><span>B</span> BudgetGuard</div>
        <p className="eyebrow">Your money, in the present tense</p>
        <h1>A budget that notices when life happens.</h1>
        <p className="auth-copy">Turn everyday transactions into calm, useful guardrails—before a difficult week becomes a difficult month.</p>
        <div className="privacy-note"><ShieldCheck size={18} /> Raw bank messages stay on your phone.</div>
      </section>
      <section className="auth-panel">
        <p className="eyebrow">Private access</p>
        <h2>{mode === "sign-in" ? "Welcome back" : mode === "sign-up" ? "Create your vault" : "Reset your password"}</h2>
        {mode !== "forgot" && (
          <div className="auth-tabs" role="tablist" aria-label="Authentication mode">
            <button type="button" role="tab" aria-selected={mode === "sign-in"} onClick={() => changeMode("sign-in")}>Sign in</button>
            <button type="button" role="tab" aria-selected={mode === "sign-up"} onClick={() => changeMode("sign-up")}>Create account</button>
          </div>
        )}
        <form onSubmit={submit}>
          <label htmlFor="email">Email address</label>
          <input id="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
          {mode !== "forgot" && (
            <>
              <label htmlFor="password">Password</label>
              <input id="password" type="password" autoComplete={mode === "sign-in" ? "current-password" : "new-password"} minLength={8} value={password} onChange={(event) => setPassword(event.target.value)} required />
            </>
          )}
          {mode === "sign-up" && (
            <>
              <label htmlFor="confirm-password">Confirm password</label>
              <input id="confirm-password" type="password" autoComplete="new-password" minLength={8} value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} required />
            </>
          )}
          {error && <p className="form-error" role="alert">{error}</p>}
          {notice && <p className="form-notice" role="status">{notice}</p>}
          <button className="primary-button" type="submit" disabled={submitting}>
            {submitting ? "Please wait…" : mode === "sign-in" ? "Sign in" : mode === "sign-up" ? "Create account" : "Send reset link"}
            {!submitting && <ArrowUpRight size={17} />}
          </button>
          <button className="auth-text-button" type="button" onClick={() => changeMode(mode === "forgot" ? "sign-in" : "forgot")}>
            {mode === "forgot" ? "Back to sign in" : "Forgot your password?"}
          </button>
        </form>
      </section>
    </main>
  );
}

function PasswordRecovery({ onDone }: { onDone: () => void }) {
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!supabase) return;
    if (password !== confirmPassword) {
      setError("The passwords do not match.");
      return;
    }
    setSaving(true);
    setError("");
    const result = await supabase.auth.updateUser({ password });
    setSaving(false);
    if (result.error) setError(result.error.message);
    else onDone();
  }

  return (
    <main className="auth-shell auth-shell--recovery">
      <section className="auth-story">
        <div className="brand brand--large"><span>B</span> BudgetGuard</div>
        <p className="eyebrow">Account recovery</p>
        <h1>Choose a new key for your financial workspace.</h1>
      </section>
      <section className="auth-panel">
        <p className="eyebrow">Secure reset</p>
        <h2>Set a new password</h2>
        <form onSubmit={submit}>
          <label htmlFor="recovery-password">New password</label>
          <input id="recovery-password" type="password" autoComplete="new-password" minLength={8} value={password} onChange={(event) => setPassword(event.target.value)} required />
          <label htmlFor="recovery-confirm-password">Confirm password</label>
          <input id="recovery-confirm-password" type="password" autoComplete="new-password" minLength={8} value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} required />
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="primary-button" type="submit" disabled={saving}>{saving ? "Updating…" : "Update password"}</button>
        </form>
      </section>
    </main>
  );
}

export function App() {
  const auth = useAuthState();
  const [view, setView] = useState<View>("overview");
  const [mobileNav, setMobileNav] = useState(false);
  const [data, setData] = useState<DashboardData | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    setData(null);
    setError("");
    if (!auth.user) return;
    void loadDashboard().then(setData).catch((reason: unknown) => {
      setError(reason instanceof Error ? reason.message : "Could not load your dashboard.");
    });
  }, [auth.user?.id]);

  if (auth.loading) return <div className="full-loader"><RefreshCw className="spin" /> Securing your workspace…</div>;
  if (!auth.user) return <SignIn />;
  if (auth.recoveringPassword) return <PasswordRecovery onDone={auth.finishRecovery} />;
  if (error) return <div className="full-loader full-loader--error"><CircleAlert /> {error}</div>;
  if (!data) return <div className="full-loader"><RefreshCw className="spin" /> Reading your ledger…</div>;

  return (
    <div className="app-shell">
      <Sidebar view={view} setView={setView} open={mobileNav} close={() => setMobileNav(false)} reviewCount={data.transactions.filter((transaction) => transaction.needsReview).length} />
      <main className="workspace">
        <header className="topbar">
          <button className="icon-button mobile-menu" onClick={() => setMobileNav(true)} aria-label="Open navigation"><Menu /></button>
          <div><p className="eyebrow">{data.month} · {data.period.status}</p><h1>{pageTitles[view]}</h1></div>
          <div className="topbar-actions">
            {!isSupabaseConfigured && <span className="demo-pill">Demo data</span>}
            <button className="icon-button" aria-label="Notifications"><Bell size={20} /><i /></button>
            <AccountMenu email={auth.user.email} demo={!isSupabaseConfigured} />
          </div>
        </header>
        {view === "overview" && <Overview data={data} goTo={setView} />}
        {view === "plan" && <PlanView data={data} setData={setData} />}
        {view === "transactions" && <TransactionsView data={data} setData={setData} />}
        {view === "accounts" && <AccountsView data={data} />}
      </main>
    </div>
  );
}

function AccountMenu({ email, demo }: { email: string | undefined; demo: boolean }) {
  const [open, setOpen] = useState(false);
  const initials = demo ? "BG" : (email?.slice(0, 2) ?? "BG").toUpperCase();

  async function signOut() {
    setOpen(false);
    await supabase?.auth.signOut();
  }

  return (
    <div className="account-menu">
      <button className="avatar" aria-label="Account menu" aria-expanded={open} onClick={() => setOpen((value) => !value)}>{initials}</button>
      {open && (
        <div className="account-popover">
          <span>{demo ? "Demo workspace" : email}</span>
          {!demo && <button type="button" onClick={() => void signOut()}>Sign out</button>}
        </div>
      )}
    </div>
  );
}

function Sidebar({ view, setView, open, close, reviewCount }: { view: View; setView: (view: View) => void; open: boolean; close: () => void; reviewCount: number }) {
  return (
    <>
      {open && <button className="nav-scrim" onClick={close} aria-label="Close navigation" />}
      <aside className={`sidebar ${open ? "sidebar--open" : ""}`}>
        <div className="sidebar-head">
          <div className="brand"><span>B</span> BudgetGuard</div>
          <button className="icon-button sidebar-close" onClick={close} aria-label="Close navigation"><X /></button>
        </div>
        <nav aria-label="Main navigation">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button key={item.id} className={view === item.id ? "active" : ""} onClick={() => { setView(item.id); close(); }}>
                <Icon size={19} /> {item.label}
                {item.id === "transactions" && reviewCount > 0 && <span className="nav-count">{reviewCount}</span>}
              </button>
            );
          })}
        </nav>
        <div className="sidebar-spacer" />
        <div className="device-card">
          <div className="device-icon"><Smartphone size={19} /></div>
          <div><strong>Galaxy connected</strong><span>Last SMS · just now</span></div>
          <CheckCircle2 size={17} className="success" />
        </div>
        <button className="settings-link"><Settings size={18} /> Settings</button>
        <div className="privacy-lock"><ShieldCheck size={17} /><span><strong>Private by design</strong>Raw SMS stays on-device</span></div>
      </aside>
    </>
  );
}

function Overview({ data, goTo }: { data: DashboardData; goTo: (view: View) => void }) {
  const cashflow = summariseCashflow(data.period, data.plannedItems);
  const budget = summariseBudgets(data.budgets);
  const needsReview = data.transactions.filter((transaction) => transaction.needsReview).length;
  const operational = data.accounts.filter((account) => account.role === "operational");
  return (
    <div className="page-content">
      <section className="hero-grid">
        <article className="safe-card">
          <div className="safe-card__copy">
            <span className="metric-label">Projected month-end surplus</span>
            <strong>{formatZar(cashflow.projectedSurplusCents)}</strong>
            <p>What remains after every known expense and savings commitment has been funded.</p>
          </div>
          <div className="dial" style={{ "--progress": `${Math.min(100, cashflow.allocationPercentage)}%` } as React.CSSProperties}>
            <div><strong>{cashflow.allocationPercentage}%</strong><span>allocated</span></div>
          </div>
          <div className="safe-card__footer"><Sparkles size={16} /> {formatZar(budget.safeToSpendTodayCents)} is available across flexible envelopes today.</div>
        </article>
        <div className="metric-stack">
          <Metric label="Expected income" value={formatZar(cashflow.plannedIncomeCents)} hint={`${formatZar(cashflow.actualIncomeCents)} received`} tone="light" />
          <Metric label="Known commitments" value={formatZar(cashflow.plannedExpenseCents)} hint={`${formatZar(cashflow.remainingToFundCents)} still to clear`} tone="dark" />
        </div>
      </section>

      <section className="account-lanes" aria-label="Operational accounts">
        <div className="section-intro"><div><p className="eyebrow">Operational accounts</p><h2>Four lanes, one plan.</h2></div><button className="text-button" onClick={() => goTo("accounts")}>Account map <ArrowUpRight size={16} /></button></div>
        <div className="lane-grid">{operational.map((account, index) => <AccountLane account={account} index={index} key={account.id} />)}</div>
      </section>

      <section className="content-grid">
        <article className="panel plan-pulse">
          <div className="panel-head"><div><p className="eyebrow">Monthly promise</p><h2>What is still ahead</h2></div><button className="text-button" onClick={() => goTo("plan")}>Open plan <ArrowUpRight size={16} /></button></div>
          <div className="promise-list">{data.plannedItems.filter((item) => plannedItemStatus(item) !== "settled").slice(0, 5).map((item) => <PlanItemRow key={item.id} item={item} data={data} />)}</div>
        </article>
        <aside className="panel attention-card">
          <div className="attention-icon"><CircleAlert size={21} /></div>
          <p className="eyebrow">Needs your eye</p>
          <h2>{needsReview} transaction{needsReview === 1 ? "" : "s"} waiting</h2>
          <p>Review an unclear movement once. The next matching bank message can follow the same category.</p>
          <button className="primary-button" onClick={() => goTo("transactions")}>Review now <ArrowUpRight size={17} /></button>
        </aside>
      </section>
      <TransactionsTable data={data} transactions={data.transactions.slice(0, 5)} compact />
    </div>
  );
}

function Metric({ label, value, hint, tone }: { label: string; value: string; hint: string; tone: "light" | "dark" }) {
  return <article className={`metric-card metric-card--${tone}`}><span>{label}</span><strong>{value}</strong><small>{hint}</small></article>;
}

function AccountLane({ account, index }: { account: Account; index: number }) {
  const icons = [WalletCards, CreditCard, PiggyBank, Sparkles];
  const Icon = icons[index % icons.length]!;
  return (
    <article className="lane-card">
      <span className="lane-number">0{index + 1}</span>
      <div className="lane-icon"><Icon size={18} /></div>
      <div><strong>{account.name}</strong><p>{account.purpose}</p></div>
      <span className="lane-balance">{formatZar(account.currentBalanceCents)}</span>
    </article>
  );
}

function PlanView({ data, setData }: { data: DashboardData; setData: (data: DashboardData) => void }) {
  const [adding, setAdding] = useState<PlannedItemDirection | null>(null);
  const [saveError, setSaveError] = useState("");
  const [query, setQuery] = useState("");
  const [expenseOrder, setExpenseOrder] = useState<ExpenseOrder>("name");
  const [paymentError, setPaymentError] = useState("");
  const [updatingPaymentId, setUpdatingPaymentId] = useState<string | null>(null);
  const summary = summariseCashflow(data.period, data.plannedItems);
  const groups = useMemo(
    () => groupPlannedItems(data.plannedItems, query, expenseOrder),
    [data.plannedItems, expenseOrder, query],
  );
  async function add(item: PlanDraft) {
    setSaveError("");
    try {
      const id = await createPlannedItem(data.period.id, item);
      setData({ ...data, plannedItems: [...data.plannedItems, { ...item, id, actualCents: 0, manuallyPaid: false, sortOrder: data.plannedItems.length + 1 }] });
      setAdding(null);
    } catch (reason) {
      setSaveError(reason instanceof Error ? reason.message : "Could not add this plan item.");
    }
  }
  async function changePaid(item: PlannedItem, paid: boolean) {
    if (updatingPaymentId) return;
    setPaymentError("");
    setUpdatingPaymentId(item.id);
    try {
      await setPlannedExpensePaid(item.id, paid);
      setData({
        ...data,
        plannedItems: data.plannedItems.map((candidate) => candidate.id === item.id
          ? { ...candidate, manuallyPaid: paid }
          : candidate),
      });
    } catch (reason) {
      setPaymentError(reason instanceof Error ? reason.message : "Could not update this expense.");
    } finally {
      setUpdatingPaymentId(null);
    }
  }
  return (
    <div className="page-content plan-page">
      <section className="plan-summary-strip">
        <div><span>Income planned</span><strong className="money-in">{formatZar(summary.plannedIncomeCents)}</strong></div><i>−</i>
        <div><span>Expenses planned</span><strong>{formatZar(summary.plannedExpenseCents)}</strong></div><i>=</i>
        <div className="surplus-cell"><span>Projected surplus</span><strong>{formatZar(summary.projectedSurplusCents)}</strong></div>
      </section>
      <section className="plan-tools" aria-label="Plan filters">
        <label className="search-box" htmlFor="plan-search"><Search size={17} /><input id="plan-search" type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search income and expenses" /></label>
        <label className="plan-order" htmlFor="expense-order"><span>Order expenses</span><select id="expense-order" value={expenseOrder} onChange={(event) => setExpenseOrder(event.target.value as ExpenseOrder)}><option value="name">Name A–Z</option><option value="amount">Amount high–low</option></select><ChevronDown size={15} /></label>
      </section>
      {paymentError && <p className="plan-payment-error" role="alert"><CircleAlert size={15} /> {paymentError}</p>}
      <section className="plan-ledger">
        <PlanColumn title="Expected income" eyebrow="Money in" items={groups.income} data={data} action={() => setAdding("income")} emptyMessage={query ? "No income matches your search." : "Add the first amount you expect this month."} />
        <ExpensePlanColumn groups={groups} data={data} action={() => setAdding("expense")} onPaidChange={changePaid} updatingPaymentId={updatingPaymentId} query={query} />
      </section>
      <section className="envelope-section">
        <div className="section-intro"><div><p className="eyebrow">Flexible spending</p><h2>Category guardrails</h2></div><p>Envelopes protect the parts of the month that can still move.</p></div>
        <div className="budget-board">
          {data.budgets.map((budget) => {
            const category = data.categories.find((item) => item.id === budget.categoryId)!;
            const used = budget.spentCents + budget.committedCents;
            const percentage = budgetPercentage(budget);
            return (
              <article className="budget-tile" key={budget.id} style={{ "--accent": category.colour } as React.CSSProperties}>
                <div className="budget-tile__head"><span>{category.name.slice(0, 1)}</span><button className="icon-button" aria-label={`Options for ${category.name}`}><MoreHorizontal /></button></div>
                <p>{category.name}</p><strong>{formatZar(budget.limitCents - used)}</strong><small>left of {formatZar(budget.limitCents)}</small>
                <div className="budget-tile__bar"><i style={{ width: `${percentage}%` }} /></div>
                <footer><span>{percentage}% used</span>{budget.committedCents > 0 && <span>{formatZar(budget.committedCents)} pending</span>}</footer>
              </article>
            );
          })}
        </div>
      </section>
      {adding && <PlanItemForm direction={adding} data={data} onCancel={() => { setAdding(null); setSaveError(""); }} onSave={add} error={saveError} />}
    </div>
  );
}

function PlanColumn({ title, eyebrow, items, data, action, emptyMessage }: { title: string; eyebrow: string; items: PlannedItem[]; data: DashboardData; action: () => void; emptyMessage: string }) {
  const total = items.reduce((sum, item) => sum + item.plannedCents, 0);
  return (
    <article className="panel plan-column">
      <div className="plan-column__head"><div><p className="eyebrow">{eyebrow}</p><h2>{title}</h2></div><button className="add-button" onClick={action}><Plus size={16} /> Add</button></div>
      <div className="plan-column__labels"><span>Item</span><span>Account</span><span>Planned</span></div>
      <div className="plan-column__rows">{items.map((item) => <PlanItemRow key={item.id} item={item} data={data} />)}{items.length === 0 && <div className="plan-empty">{emptyMessage}</div>}</div>
      <footer><span>Total</span><strong>{formatZar(total)}</strong></footer>
    </article>
  );
}

function ExpensePlanColumn({ groups, data, action, onPaidChange, updatingPaymentId, query }: {
  groups: ReturnType<typeof groupPlannedItems>;
  data: DashboardData;
  action: () => void;
  onPaidChange: (item: PlannedItem, paid: boolean) => Promise<void>;
  updatingPaymentId: string | null;
  query: string;
}) {
  const allExpenses = [...groups.unpaidExpenses, ...groups.paidExpenses];
  const total = allExpenses.reduce((sum, item) => sum + item.plannedCents, 0);
  return (
    <article className="panel plan-column plan-column--expenses">
      <div className="plan-column__head"><div><p className="eyebrow">Money out</p><h2>Known expenses</h2></div><button className="add-button" onClick={action}><Plus size={16} /> Add</button></div>
      <div className="plan-column__labels plan-column__labels--action"><span>Item</span><span>Account</span><span>Planned</span><span>Status</span></div>
      <div className="expense-group">
        <div className="expense-group__head"><span>To pay</span><strong>{groups.unpaidExpenses.length}</strong></div>
        <div className="plan-column__rows">{groups.unpaidExpenses.map((item) => <PlanItemRow key={item.id} item={item} data={data} onPaidChange={onPaidChange} updating={updatingPaymentId === item.id} />)}{groups.unpaidExpenses.length === 0 && <div className="plan-empty plan-empty--compact">{query && groups.paidExpenses.length === 0 ? "No expenses match your search." : "Nothing left to pay."}</div>}</div>
      </div>
      <div className="expense-group expense-group--paid">
        <div className="expense-group__head"><span>Paid</span><strong>{groups.paidExpenses.length}</strong></div>
        <div className="plan-column__rows">{groups.paidExpenses.map((item) => <PlanItemRow key={item.id} item={item} data={data} onPaidChange={onPaidChange} updating={updatingPaymentId === item.id} />)}{groups.paidExpenses.length === 0 && <div className="plan-empty plan-empty--compact">Paid expenses will collect here.</div>}</div>
      </div>
      <footer><span>Visible total</span><strong>{formatZar(total)}</strong></footer>
    </article>
  );
}

function PlanItemRow({ item, data, onPaidChange, updating = false }: { item: PlannedItem; data: DashboardData; onPaidChange?: (item: PlannedItem, paid: boolean) => Promise<void>; updating?: boolean }) {
  const account = data.accounts.find((candidate) => candidate.id === item.accountId);
  const category = data.categories.find((candidate) => candidate.id === item.categoryId);
  const status = plannedItemStatus(item);
  const progress = item.plannedCents > 0 ? Math.min(100, Math.round((item.actualCents / item.plannedCents) * 100)) : 0;
  return (
    <div className={`plan-item ${onPaidChange ? "plan-item--action" : ""}`}>
      <div className="plan-item__name"><span style={{ background: category?.colour ?? "#e8e7e0" }}>{status === "settled" ? <Check size={13} /> : item.name.slice(0, 1)}</span><div><strong>{item.name}</strong><small>{item.dueDay ? `Due day ${item.dueDay}` : "Flexible timing"} · {status}</small></div></div>
      <span className="plan-account">{account?.name ?? "Unassigned"}</span>
      <div className="plan-amount"><strong>{formatZar(item.plannedCents)}</strong><span><i style={{ width: `${progress}%` }} /></span></div>
      {onPaidChange && (item.manuallyPaid
        ? <button className="paid-action paid-action--undo" type="button" disabled={updating} onClick={() => void onPaidChange(item, false)}>{updating ? "Saving…" : "Undo paid"}</button>
        : status === "settled"
          ? <span className="paid-action paid-action--settled"><Check size={13} /> Paid</span>
          : <button className="paid-action" type="button" disabled={updating} onClick={() => void onPaidChange(item, true)}>{updating ? "Saving…" : "Mark paid"}</button>)}
    </div>
  );
}

type PlanDraft = Omit<PlannedItem, "id" | "actualCents" | "sortOrder" | "manuallyPaid">;

function PlanItemForm({ direction, data, onCancel, onSave, error }: { direction: PlannedItemDirection; data: DashboardData; onCancel: () => void; onSave: (item: PlanDraft) => Promise<void>; error: string }) {
  const [name, setName] = useState("");
  const [amount, setAmount] = useState("");
  const [accountId, setAccountId] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [dueDay, setDueDay] = useState("");
  const [saving, setSaving] = useState(false);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const cents = Math.round(Number(amount.replace(/,/g, "")) * 100);
    if (!Number.isFinite(cents) || cents <= 0) return;
    setSaving(true);
    await onSave({
      direction, kind: direction === "income" ? "income" : "fixed_expense",
      name: name.trim(), plannedCents: cents, accountId: accountId || null,
      categoryId: categoryId || null, dueDay: dueDay ? Number(dueDay) : null,
    });
    setSaving(false);
  }
  return (
    <div className="modal-scrim" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) onCancel(); }}>
      <form className="plan-form" role="dialog" aria-modal="true" aria-labelledby="plan-form-title" onSubmit={submit}>
        <div className="plan-form__head"><div><p className="eyebrow">{direction === "income" ? "Money in" : "Money out"}</p><h2 id="plan-form-title">Add {direction}</h2></div><button type="button" className="icon-button" onClick={onCancel} aria-label="Close"><X /></button></div>
        <label>What is it called?<input autoFocus value={name} onChange={(event) => setName(event.target.value)} placeholder={direction === "income" ? "e.g. Salary" : "e.g. Medical cover"} required /></label>
        <div className="form-grid">
          <label>Planned amount (R)<input inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="0.00" required /></label>
          <label>Due day<input type="number" min="1" max="31" value={dueDay} onChange={(event) => setDueDay(event.target.value)} placeholder="Optional" /></label>
        </div>
        <label>Account<select value={accountId} onChange={(event) => setAccountId(event.target.value)}><option value="">Choose later</option>{data.accounts.map((account) => <option key={account.id} value={account.id}>{account.name}</option>)}</select></label>
        <label>Category<select value={categoryId} onChange={(event) => setCategoryId(event.target.value)}><option value="">Uncategorised</option>{data.categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
        {error && <p className="form-error">{error}</p>}
        <div className="plan-form__actions"><button type="button" className="secondary-button" onClick={onCancel}>Cancel</button><button className="primary-button" type="submit" disabled={saving}>{saving ? "Saving…" : "Add to plan"}</button></div>
      </form>
    </div>
  );
}

function AccountsView({ data }: { data: DashboardData }) {
  const operational = data.accounts.filter((account) => account.role === "operational");
  const other = data.accounts.filter((account) => account.role !== "operational");
  return (
    <div className="page-content accounts-page">
      <section className="account-map-hero">
        <p className="eyebrow">Cash-flow routing</p><h2>Your operational system</h2>
        <p>These are working accounts. The monthly plan routes each income and expense to the account responsible for it.</p>
        <div className="account-route"><span>Income</span><i /><span>Known costs</span><i /><span>Daily needs</span><i /><span>Surplus</span></div>
      </section>
      <section className="accounts-section">
        <div className="section-intro"><div><p className="eyebrow">01 · Working money</p><h2>Operational accounts</h2></div><span>{operational.length} accounts</span></div>
        <div className="account-card-grid">{operational.map((account, index) => <AccountCard account={account} index={index} key={account.id} />)}</div>
      </section>
      <section className="accounts-section">
        <div className="section-intro"><div><p className="eyebrow">02 · Balance sheet</p><h2>Savings, property and rewards</h2></div><span>{other.length} accounts</span></div>
        <div className="account-card-grid account-card-grid--other">{other.map((account, index) => <AccountCard account={account} index={index + 4} key={account.id} />)}</div>
      </section>
    </div>
  );
}

function AccountCard({ account, index }: { account: Account; index: number }) {
  const Icon = account.type === "home_loan" ? House : account.type === "credit_card" ? CreditCard : account.type === "savings" ? PiggyBank : account.type === "rewards" ? Sparkles : WalletCards;
  const balanceLabel = account.type === "home_loan" ? "Outstanding" : account.type === "rewards" ? "Rewards value" : "Current balance";
  return (
    <article className={`account-card account-card--${account.role}`}>
      <header><span className="account-card__icon"><Icon size={19} /></span><span className="account-order">{String(index + 1).padStart(2, "0")}</span></header>
      <p className="eyebrow">{account.type.replace("_", " ")} · {account.mask}</p><h3>{account.name}</h3><p>{account.purpose}</p>
      <footer><span>{balanceLabel}</span><strong>{formatZar(Math.abs(account.currentBalanceCents))}</strong></footer>
    </article>
  );
}

function TransactionsView({ data, setData }: { data: DashboardData; setData: (data: DashboardData) => void }) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<"all" | "review" | "pending">("all");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [sortingId, setSortingId] = useState<string | null>(null);
  const [lastAssignment, setLastAssignment] = useState<{ transaction: Transaction; categoryId: string } | null>(null);
  const [sortError, setSortError] = useState("");
  const unplanned = useMemo(
    () => data.transactions.filter(isUnplannedPayment),
    [data.transactions],
  );
  const filtered = useMemo(() => data.transactions.filter((transaction) => {
    const matchesQuery = `${transaction.merchant} ${transaction.description}`.toLowerCase().includes(query.toLowerCase());
    const matchesFilter = filter === "all" || (filter === "review" ? transaction.needsReview : transaction.status === "pending");
    return matchesQuery && matchesFilter;
  }), [data.transactions, filter, query]);
  async function assignCategory(transactionId: string, categoryId: string, remember = false) {
    const previous = data.transactions.find((transaction) => transaction.id === transactionId);
    if (!previous || sortingId) return;
    setSortingId(transactionId);
    setSortError("");
    const next = { ...data, transactions: data.transactions.map((transaction) => transaction.id === transactionId ? { ...transaction, categoryId, needsReview: false } : transaction) };
    setData(next);
    setSelectedId(null);
    if (remember) setLastAssignment({ transaction: previous, categoryId });
    try {
      await updateTransactionCategory(transactionId, categoryId);
    } catch {
      setData(data);
      setLastAssignment(null);
      setSortError("That payment could not be moved. It is still waiting in the tray.");
    } finally {
      setSortingId(null);
    }
  }
  async function undoAssignment() {
    if (!lastAssignment || sortingId) return;
    const { transaction } = lastAssignment;
    setData({
      ...data,
      transactions: data.transactions.map((candidate) => candidate.id === transaction.id ? transaction : candidate),
    });
    setLastAssignment(null);
    try {
      await updateTransactionCategory(transaction.id, transaction.categoryId, transaction.needsReview);
    } catch {
      setData(data);
      setSortError("The undo could not be saved.");
    }
  }
  async function removeFromBucket(transactionId: string) {
    const previous = data.transactions.find((transaction) => transaction.id === transactionId);
    if (!previous?.categoryId || sortingId) return;
    setSortingId(transactionId);
    setSortError("");
    setData({
      ...data,
      transactions: data.transactions.map((transaction) => transaction.id === transactionId
        ? { ...transaction, categoryId: null, needsReview: true }
        : transaction),
    });
    setLastAssignment({ transaction: previous, categoryId: previous.categoryId });
    try {
      await updateTransactionCategory(transactionId, null, true);
    } catch {
      setData(data);
      setLastAssignment(null);
      setSortError("That payment could not be moved out of its bucket.");
    } finally {
      setSortingId(null);
    }
  }
  return (
    <div className="page-content">
      <QuickSortBoard
        data={data}
        transactions={unplanned}
        selectedId={selectedId}
        sortingId={sortingId}
        onSelect={(transactionId) => setSelectedId(selectedId === transactionId ? null : transactionId)}
        onAssign={(transactionId, categoryId) => void assignCategory(transactionId, categoryId, true)}
        lastAssignment={lastAssignment}
        onUndo={() => void undoAssignment()}
        error={sortError}
      />
      <section className="transaction-toolbar">
        <label className="search-box"><Search size={18} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search merchant or description" /></label>
        <div className="filter-tabs" role="group" aria-label="Transaction filters">
          {(["all", "review", "pending"] as const).map((item) => <button key={item} className={filter === item ? "active" : ""} onClick={() => setFilter(item)}>{item === "all" ? "All" : item === "review" ? "Needs review" : "Pending"}</button>)}
        </div>
      </section>
      <TransactionsTable
        data={data}
        transactions={filtered}
        onAssignCategory={assignCategory}
        onRemoveCategory={(transactionId) => void removeFromBucket(transactionId)}
      />
    </div>
  );
}

function QuickSortBoard({
  data,
  transactions,
  selectedId,
  sortingId,
  onSelect,
  onAssign,
  lastAssignment,
  onUndo,
  error,
}: {
  data: DashboardData;
  transactions: Transaction[];
  selectedId: string | null;
  sortingId: string | null;
  onSelect: (transactionId: string) => void;
  onAssign: (transactionId: string, categoryId: string) => void;
  lastAssignment: { transaction: Transaction; categoryId: string } | null;
  onUndo: () => void;
  error: string;
}) {
  const selected = transactions.find((transaction) => transaction.id === selectedId);
  const buckets = data.categories.filter((category) => !["income", "savings-category"].includes(category.id));
  return (
    <section className="sort-board" aria-labelledby="sort-title">
      <div className="sort-board__head">
        <div>
          <p className="eyebrow">Quick sort</p>
          <h2 id="sort-title">Unplanned payments</h2>
          <p>Drag a card into a bucket, or select it and tap a bucket on your phone.</p>
        </div>
        <span className="queue-count"><strong>{transactions.length}</strong> waiting</span>
      </div>
      <div className="sort-workbench">
        <div className="payment-tray" aria-label="Payments waiting to be sorted">
          <div className="tray-label"><Inbox size={15} /><span>Payment tray</span></div>
          {transactions.map((transaction, index) => {
            const account = data.accounts.find((candidate) => candidate.id === transaction.accountId);
            const isSelected = selectedId === transaction.id;
            return (
              <button
                className={`payment-card ${isSelected ? "payment-card--selected" : ""}`}
                draggable
                key={transaction.id}
                onClick={() => onSelect(transaction.id)}
                onDragStart={(event) => {
                  event.dataTransfer.setData("text/plain", transaction.id);
                  event.dataTransfer.effectAllowed = "move";
                  onSelect(transaction.id);
                }}
                disabled={sortingId === transaction.id}
                style={{ "--card-index": index } as React.CSSProperties}
                aria-pressed={isSelected}
              >
                <GripVertical size={16} className="payment-grip" />
                <span className="payment-card__main"><strong>{transaction.merchant}</strong><small>{account?.name} · {new Intl.DateTimeFormat("en-ZA", { day: "2-digit", month: "short" }).format(new Date(transaction.occurredAt))}</small></span>
                <strong className="payment-card__amount">{formatZar(Math.abs(transaction.amountCents))}</strong>
              </button>
            );
          })}
          {transactions.length === 0 && (
            <div className="tray-empty"><CheckCircle2 /><strong>Tray cleared</strong><span>Every unplanned payment has a type.</span></div>
          )}
        </div>
        <div className="bucket-grid" aria-label="Payment type buckets">
          {buckets.map((category) => {
            const matchingBudget = data.budgets.find((budget) => budget.categoryId === category.id);
            const remaining = matchingBudget ? matchingBudget.limitCents - matchingBudget.spentCents - matchingBudget.committedCents : null;
            return (
              <button
                className={`sort-bucket ${selected ? "sort-bucket--ready" : ""}`}
                key={category.id}
                style={{ "--bucket-colour": category.colour } as React.CSSProperties}
                onClick={() => selected && onAssign(selected.id, category.id)}
                onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = "move"; }}
                onDrop={(event) => {
                  event.preventDefault();
                  const transactionId = event.dataTransfer.getData("text/plain");
                  if (transactionId) onAssign(transactionId, category.id);
                }}
                aria-label={selected ? `Put ${selected.merchant} in ${category.name}` : `${category.name} bucket`}
                aria-disabled={!selected}
              >
                <span>{category.name.slice(0, 1)}</span>
                <strong>{category.name}</strong>
                <small>{remaining === null ? "No limit set" : `${formatZar(Math.max(0, remaining))} left`}</small>
              </button>
            );
          })}
        </div>
      </div>
      <div className="sort-board__footer" aria-live="polite">
        <span>{selected ? `“${selected.merchant}” selected — choose its bucket.` : "Select any payment card to start sorting."}</span>
        {lastAssignment && <button onClick={onUndo}><Undo2 size={14} /> Undo {lastAssignment.transaction.merchant}</button>}
        {error && <span className="sort-error"><CircleAlert size={14} /> {error}</span>}
      </div>
    </section>
  );
}

function TransactionsTable({
  data,
  transactions,
  compact = false,
  onAssignCategory,
  onRemoveCategory,
}: {
  data: DashboardData;
  transactions: Transaction[];
  compact?: boolean;
  onAssignCategory?: (transactionId: string, categoryId: string) => void;
  onRemoveCategory?: (transactionId: string) => void;
}) {
  return (
    <section className="panel transaction-panel">
      <div className="panel-head"><div><p className="eyebrow">Money movement</p><h2>{compact ? "Recent transactions" : `${transactions.length} transactions`}</h2></div>{compact && <button className="icon-button"><MoreHorizontal /></button>}</div>
      <div className="transaction-list">
        {transactions.map((transaction) => {
          const category = data.categories.find((item) => item.id === transaction.categoryId);
          const account = data.accounts.find((item) => item.id === transaction.accountId);
          const incoming = transaction.amountCents > 0;
          const FlowIcon = incoming ? ArrowDownLeft : ArrowUpRight;
          return (
            <div className={`transaction-row ${transaction.needsReview ? "transaction-row--review" : ""}`} key={transaction.id}>
              <div className={`transaction-flow ${incoming ? "incoming" : "outgoing"}`}><FlowIcon size={18} /></div>
              <div className="transaction-main"><strong>{transaction.merchant}</strong><span>{new Intl.DateTimeFormat("en-ZA", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }).format(new Date(transaction.occurredAt))} · {account?.mask}</span></div>
              <div className="transaction-category">
                {transaction.needsReview && onAssignCategory ? (
                  <label className="category-select"><span className="sr-only">Choose category</span><select defaultValue="" onChange={(event) => onAssignCategory(transaction.id, event.target.value)}><option value="" disabled>Choose category</option>{data.categories.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select><ChevronDown size={14} /></label>
                ) : category ? (
                  <span className="bucketed-pill" style={{ "--category-colour": category.colour } as React.CSSProperties}>
                    <span>{category.name}</span>
                    {onRemoveCategory
                      && transaction.amountCents < 0
                      && transaction.plannedItemIds.length === 0
                      && !["transfer", "reversal"].includes(transaction.kind)
                      && <button onClick={() => onRemoveCategory(transaction.id)} aria-label={`Move ${transaction.merchant} out of ${category.name}`} title="Move out of bucket"><X size={11} /></button>}
                  </span>
                ) : <span className="category-pill" style={{ "--category-colour": "#E8E7E0" } as React.CSSProperties}>Transfer</span>}
                {transaction.status === "pending" && <span className="status-pill">Pending</span>}
                {transaction.plannedItemIds.length > 0 && <span className="status-pill status-pill--planned">Planned</span>}
              </div>
              <strong className={incoming ? "amount amount--in" : "amount"}>{formatZar(transaction.amountCents, { sign: incoming })}</strong>
            </div>
          );
        })}
        {transactions.length === 0 && <div className="empty-state"><Search /><strong>No transactions found</strong><span>Try a different search or filter.</span></div>}
      </div>
    </section>
  );
}
