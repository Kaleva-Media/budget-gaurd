create extension if not exists pgcrypto with schema extensions;

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default '',
  currency text not null default 'ZAR' check (currency ~ '^[A-Z]{3}$'),
  timezone text not null default 'Africa/Johannesburg',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  name text not null,
  platform text not null default 'android' check (platform in ('android')),
  app_version text,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  unique (user_id, id)
);

create table public.accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  institution text not null default 'absa',
  external_key text not null,
  name text not null,
  type text not null check (type in ('cheque', 'savings', 'credit_card', 'home_loan', 'rewards', 'cash')),
  role text not null default 'operational' check (role in ('operational', 'savings', 'liability', 'rewards')),
  purpose text not null default '',
  mask text not null,
  current_balance_cents bigint not null default 0,
  credit_limit_cents bigint check (credit_limit_cents is null or credit_limit_cents >= 0),
  include_in_safe_to_spend boolean not null default false,
  display_order integer not null default 0,
  is_active boolean not null default true,
  last_synced_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, institution, external_key),
  unique (user_id, id)
);

create table public.categories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  name text not null check (char_length(name) between 1 and 60),
  colour text not null default '#E8E7E0' check (colour ~ '^#[0-9A-Fa-f]{6}$'),
  icon text not null default 'circle',
  system_key text,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique nulls not distinct (user_id, system_key),
  unique (user_id, id)
);

create table public.budget_periods (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  starts_on date not null check (starts_on = date_trunc('month', starts_on)::date),
  status text not null default 'draft' check (status in ('draft', 'active', 'closed')),
  carryover_cents bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, starts_on),
  unique (user_id, id)
);

create table public.budgets (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  category_id uuid not null,
  period_start date not null check (period_start = date_trunc('month', period_start)::date),
  limit_cents bigint not null check (limit_cents >= 0),
  rollover boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (user_id, category_id) references public.categories (user_id, id) on delete cascade,
  unique (user_id, category_id, period_start),
  unique (user_id, id)
);

create table public.transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  account_id uuid not null,
  category_id uuid,
  device_id uuid,
  related_transaction_id uuid,
  occurred_on date not null,
  occurred_at timestamptz,
  amount_cents bigint not null check (amount_cents <> 0),
  available_balance_cents bigint,
  status text not null check (status in ('pending', 'posted', 'reversed', 'failed')),
  kind text not null check (kind in ('card_purchase', 'transfer', 'scheduled_payment', 'cash_withdrawal', 'fee', 'income', 'reversal', 'other')),
  source text not null check (source in ('sms', 'statement', 'manual', 'bank_api')),
  source_fingerprint text not null check (char_length(source_fingerprint) = 64),
  merchant text,
  description text,
  reference text,
  parser_version integer,
  parse_confidence numeric(4, 3) check (parse_confidence between 0 and 1),
  needs_review boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (user_id, account_id) references public.accounts (user_id, id) on delete cascade,
  foreign key (user_id, category_id) references public.categories (user_id, id) on delete restrict,
  foreign key (user_id, device_id) references public.devices (user_id, id) on delete restrict,
  foreign key (user_id, related_transaction_id) references public.transactions (user_id, id) on delete restrict,
  unique (user_id, source, source_fingerprint),
  unique (user_id, id)
);

create table public.planned_items (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  budget_period_id uuid not null,
  direction text not null check (direction in ('income', 'expense')),
  kind text not null check (kind in ('income', 'fixed_expense', 'variable_expense', 'savings', 'debt_payment')),
  name text not null check (char_length(name) between 1 and 100),
  planned_cents bigint not null check (planned_cents > 0),
  account_id uuid,
  category_id uuid,
  due_day smallint check (due_day between 1 and 31),
  recurrence text not null default 'monthly' check (recurrence in ('once', 'monthly')),
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (
    (direction = 'income' and kind = 'income')
    or (direction = 'expense' and kind <> 'income')
  ),
  foreign key (user_id, budget_period_id) references public.budget_periods (user_id, id) on delete cascade,
  foreign key (user_id, account_id) references public.accounts (user_id, id) on delete restrict,
  foreign key (user_id, category_id) references public.categories (user_id, id) on delete restrict,
  unique (user_id, id)
);

create table public.planned_item_matches (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  planned_item_id uuid not null,
  transaction_id uuid not null,
  amount_cents bigint not null check (amount_cents > 0),
  created_at timestamptz not null default now(),
  foreign key (user_id, planned_item_id) references public.planned_items (user_id, id) on delete cascade,
  foreign key (user_id, transaction_id) references public.transactions (user_id, id) on delete cascade,
  unique (planned_item_id, transaction_id)
);

create index devices_user_id_idx on public.devices (user_id);
create index accounts_user_id_idx on public.accounts (user_id);
create index categories_user_id_idx on public.categories (user_id);
create index budget_periods_user_id_idx on public.budget_periods (user_id, starts_on desc);
create index budgets_user_id_period_idx on public.budgets (user_id, period_start);
create index budgets_category_id_idx on public.budgets (category_id);
create index transactions_user_occurred_idx on public.transactions (user_id, occurred_on desc, occurred_at desc);
create index transactions_account_id_idx on public.transactions (account_id);
create index transactions_category_id_idx on public.transactions (category_id);
create index transactions_device_id_idx on public.transactions (device_id);
create index transactions_related_id_idx on public.transactions (related_transaction_id);
create index transactions_review_idx on public.transactions (user_id, needs_review) where needs_review;
create index planned_items_user_period_idx on public.planned_items (user_id, budget_period_id, direction, sort_order);
create index planned_items_account_id_idx on public.planned_items (account_id);
create index planned_items_category_id_idx on public.planned_items (category_id);
create index planned_item_matches_user_id_idx on public.planned_item_matches (user_id);
create index planned_item_matches_planned_item_id_idx on public.planned_item_matches (planned_item_id);
create index planned_item_matches_transaction_id_idx on public.planned_item_matches (transaction_id);

create function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

revoke all on function public.set_updated_at() from public, anon, authenticated;

create trigger profiles_set_updated_at before update on public.profiles
for each row execute function public.set_updated_at();
create trigger accounts_set_updated_at before update on public.accounts
for each row execute function public.set_updated_at();
create trigger categories_set_updated_at before update on public.categories
for each row execute function public.set_updated_at();
create trigger budget_periods_set_updated_at before update on public.budget_periods
for each row execute function public.set_updated_at();
create trigger budgets_set_updated_at before update on public.budgets
for each row execute function public.set_updated_at();
create trigger transactions_set_updated_at before update on public.transactions
for each row execute function public.set_updated_at();
create trigger planned_items_set_updated_at before update on public.planned_items
for each row execute function public.set_updated_at();

alter table public.profiles enable row level security;
alter table public.devices enable row level security;
alter table public.accounts enable row level security;
alter table public.categories enable row level security;
alter table public.budget_periods enable row level security;
alter table public.budgets enable row level security;
alter table public.transactions enable row level security;
alter table public.planned_items enable row level security;
alter table public.planned_item_matches enable row level security;

revoke all on table public.profiles, public.devices, public.accounts, public.categories, public.budget_periods, public.budgets, public.transactions, public.planned_items, public.planned_item_matches from anon;
grant select, insert, update, delete on table public.profiles, public.devices, public.accounts, public.categories, public.budget_periods, public.budgets, public.transactions, public.planned_items, public.planned_item_matches to authenticated;

create policy "profiles_select_own" on public.profiles for select to authenticated
using ((select auth.uid()) = id);
create policy "profiles_insert_own" on public.profiles for insert to authenticated
with check ((select auth.uid()) = id);
create policy "profiles_update_own" on public.profiles for update to authenticated
using ((select auth.uid()) = id) with check ((select auth.uid()) = id);
create policy "profiles_delete_own" on public.profiles for delete to authenticated
using ((select auth.uid()) = id);

create policy "devices_select_own" on public.devices for select to authenticated
using ((select auth.uid()) = user_id);
create policy "devices_insert_own" on public.devices for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "devices_update_own" on public.devices for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "devices_delete_own" on public.devices for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "accounts_select_own" on public.accounts for select to authenticated
using ((select auth.uid()) = user_id);
create policy "accounts_insert_own" on public.accounts for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "accounts_update_own" on public.accounts for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "accounts_delete_own" on public.accounts for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "categories_select_own" on public.categories for select to authenticated
using ((select auth.uid()) = user_id);
create policy "categories_insert_own" on public.categories for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "categories_update_own" on public.categories for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "categories_delete_own" on public.categories for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "budget_periods_select_own" on public.budget_periods for select to authenticated
using ((select auth.uid()) = user_id);
create policy "budget_periods_insert_own" on public.budget_periods for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "budget_periods_update_own" on public.budget_periods for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "budget_periods_delete_own" on public.budget_periods for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "budgets_select_own" on public.budgets for select to authenticated
using ((select auth.uid()) = user_id);
create policy "budgets_insert_own" on public.budgets for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "budgets_update_own" on public.budgets for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "budgets_delete_own" on public.budgets for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "transactions_select_own" on public.transactions for select to authenticated
using ((select auth.uid()) = user_id);
create policy "transactions_insert_own" on public.transactions for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "transactions_update_own" on public.transactions for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "transactions_delete_own" on public.transactions for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "planned_items_select_own" on public.planned_items for select to authenticated
using ((select auth.uid()) = user_id);
create policy "planned_items_insert_own" on public.planned_items for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "planned_items_update_own" on public.planned_items for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "planned_items_delete_own" on public.planned_items for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "planned_item_matches_select_own" on public.planned_item_matches for select to authenticated
using ((select auth.uid()) = user_id);
create policy "planned_item_matches_insert_own" on public.planned_item_matches for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "planned_item_matches_update_own" on public.planned_item_matches for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "planned_item_matches_delete_own" on public.planned_item_matches for delete to authenticated
using ((select auth.uid()) = user_id);

create view public.budget_progress
with (security_invoker = true)
as
select
  b.id,
  b.user_id,
  b.category_id,
  b.period_start,
  b.limit_cents,
  coalesce(sum(abs(t.amount_cents)) filter (
    where t.status = 'posted'
      and t.amount_cents < 0
      and t.kind not in ('transfer', 'reversal')
  ), 0)::bigint as spent_cents,
  coalesce(sum(abs(t.amount_cents)) filter (
    where t.status = 'pending'
      and t.amount_cents < 0
      and t.kind not in ('transfer', 'reversal')
  ), 0)::bigint as committed_cents
from public.budgets b
left join public.transactions t
  on t.user_id = b.user_id
 and t.category_id = b.category_id
 and t.occurred_on >= b.period_start
 and t.occurred_on < (b.period_start + interval '1 month')::date
group by b.id;

revoke all on table public.budget_progress from anon;
grant select on table public.budget_progress to authenticated;

create view public.planned_item_progress
with (security_invoker = true)
as
select
  p.id,
  p.user_id,
  p.budget_period_id,
  p.direction,
  p.kind,
  p.name,
  p.planned_cents,
  p.account_id,
  p.category_id,
  p.due_day,
  p.recurrence,
  p.sort_order,
  coalesce(sum(m.amount_cents), 0)::bigint as actual_cents
from public.planned_items p
left join public.planned_item_matches m
  on m.user_id = p.user_id
 and m.planned_item_id = p.id
group by p.id;

revoke all on table public.planned_item_progress from anon;
grant select on table public.planned_item_progress to authenticated;
