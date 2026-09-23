create table public.debts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  entity_id uuid not null,
  name text not null check (char_length(trim(name)) between 1 and 120),
  debt_type text not null check (debt_type in (
    'credit_card', 'personal_loan', 'overdraft', 'vehicle_finance',
    'home_loan', 'store_account', 'tax', 'medical', 'other'
  )),
  balance_cents bigint not null check (balance_cents > 0),
  annual_interest_bps integer not null check (annual_interest_bps between 0 and 100000),
  minimum_payment_cents bigint not null check (minimum_payment_cents > 0),
  remaining_term_months integer check (remaining_term_months between 1 and 1200),
  due_day integer check (due_day between 1 and 31),
  secured boolean not null default false,
  in_arrears boolean not null default false,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint debts_user_entity_fkey
    foreign key (user_id, entity_id)
    references public.entities (user_id, id)
    on delete cascade
);

create index debts_user_entity_active_idx
  on public.debts (user_id, entity_id, is_active, annual_interest_bps desc);

create table public.debt_preferences (
  user_id uuid not null references auth.users (id) on delete cascade,
  entity_id uuid not null,
  goal text not null default 'balanced'
    check (goal in ('lowest_cost', 'quick_wins', 'balanced')),
  consolidation_apr_bps integer
    check (consolidation_apr_bps between 0 and 100000),
  consolidation_term_months integer
    check (consolidation_term_months between 1 and 1200),
  consolidation_fees_cents bigint not null default 0
    check (consolidation_fees_cents >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, entity_id),
  constraint debt_preferences_user_entity_fkey
    foreign key (user_id, entity_id)
    references public.entities (user_id, id)
    on delete cascade,
  constraint debt_preferences_offer_complete_check check (
    (consolidation_apr_bps is null and consolidation_term_months is null)
    or
    (consolidation_apr_bps is not null and consolidation_term_months is not null)
  )
);

create trigger debts_set_updated_at before update on public.debts
for each row execute function public.set_updated_at();

create trigger debt_preferences_set_updated_at before update on public.debt_preferences
for each row execute function public.set_updated_at();

alter table public.debts enable row level security;
alter table public.debt_preferences enable row level security;

revoke all on table public.debts, public.debt_preferences from anon;
grant select, insert, update, delete on table public.debts, public.debt_preferences to authenticated;

create policy "debts_select_own" on public.debts for select to authenticated
using ((select auth.uid()) = user_id);

create policy "debts_insert_own_entity" on public.debts for insert to authenticated
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1 from public.entities e
    where e.id = entity_id and e.user_id = (select auth.uid())
  )
);

create policy "debts_update_own_entity" on public.debts for update to authenticated
using ((select auth.uid()) = user_id)
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1 from public.entities e
    where e.id = entity_id and e.user_id = (select auth.uid())
  )
);

create policy "debts_delete_own" on public.debts for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "debt_preferences_select_own" on public.debt_preferences for select to authenticated
using ((select auth.uid()) = user_id);

create policy "debt_preferences_insert_own_entity" on public.debt_preferences for insert to authenticated
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1 from public.entities e
    where e.id = entity_id and e.user_id = (select auth.uid())
  )
);

create policy "debt_preferences_update_own_entity" on public.debt_preferences for update to authenticated
using ((select auth.uid()) = user_id)
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1 from public.entities e
    where e.id = entity_id and e.user_id = (select auth.uid())
  )
);

create policy "debt_preferences_delete_own" on public.debt_preferences for delete to authenticated
using ((select auth.uid()) = user_id);
