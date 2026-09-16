create table public.entities (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  name text not null check (char_length(trim(name)) between 1 and 80),
  kind text not null default 'company' check (kind in ('personal', 'company', 'other')),
  is_default boolean not null default false,
  is_active boolean not null default true,
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, id)
);

create unique index entities_user_name_key on public.entities (user_id, lower(trim(name)));
create unique index entities_one_default_per_user_idx on public.entities (user_id) where is_default;
create index entities_user_order_idx on public.entities (user_id, is_active, display_order, name);

alter table public.entities enable row level security;
revoke all on table public.entities from anon;
grant select, insert, update, delete on table public.entities to authenticated;

create policy "entities_select_own" on public.entities for select to authenticated
using ((select auth.uid()) = user_id);
create policy "entities_insert_own" on public.entities for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "entities_update_own" on public.entities for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "entities_delete_own" on public.entities for delete to authenticated
using ((select auth.uid()) = user_id);

create trigger entities_set_updated_at before update on public.entities
for each row execute function public.set_updated_at();

insert into public.entities (user_id, name, kind, is_default, display_order)
select id, 'Personal', 'personal', true, 0
from auth.users;

alter table public.accounts add column entity_id uuid;
alter table public.budget_periods add column entity_id uuid;
alter table public.budgets add column entity_id uuid;
alter table public.transactions add column entity_id uuid;
alter table public.planned_items add column entity_id uuid;

update public.accounts a
set entity_id = e.id
from public.entities e
where e.user_id = a.user_id and e.is_default;

update public.budget_periods p
set entity_id = e.id
from public.entities e
where e.user_id = p.user_id and e.is_default;

update public.budgets b
set entity_id = e.id
from public.entities e
where e.user_id = b.user_id and e.is_default;

update public.transactions t
set entity_id = e.id
from public.entities e
where e.user_id = t.user_id and e.is_default;

update public.planned_items p
set entity_id = e.id
from public.entities e
where e.user_id = p.user_id and e.is_default;

alter table public.accounts alter column entity_id set not null;
alter table public.budget_periods alter column entity_id set not null;
alter table public.budgets alter column entity_id set not null;
alter table public.transactions alter column entity_id set not null;
alter table public.planned_items alter column entity_id set not null;

alter table public.accounts
  add constraint accounts_user_entity_fkey
  foreign key (user_id, entity_id) references public.entities (user_id, id) on delete restrict,
  add constraint accounts_user_entity_id_key unique (user_id, entity_id, id);

alter table public.budget_periods
  drop constraint budget_periods_user_id_starts_on_key,
  add constraint budget_periods_user_entity_fkey
  foreign key (user_id, entity_id) references public.entities (user_id, id) on delete cascade,
  add constraint budget_periods_user_entity_starts_on_key unique (user_id, entity_id, starts_on),
  add constraint budget_periods_user_entity_id_key unique (user_id, entity_id, id);

alter table public.budgets
  drop constraint budgets_user_id_category_id_period_start_key,
  add constraint budgets_user_entity_fkey
  foreign key (user_id, entity_id) references public.entities (user_id, id) on delete cascade,
  add constraint budgets_user_entity_category_period_key unique (user_id, entity_id, category_id, period_start);

alter table public.transactions
  drop constraint transactions_user_id_account_id_fkey,
  add constraint transactions_user_entity_fkey
  foreign key (user_id, entity_id) references public.entities (user_id, id) on delete cascade,
  add constraint transactions_user_entity_id_key unique (user_id, entity_id, id),
  add constraint transactions_user_entity_account_fkey
  foreign key (user_id, entity_id, account_id)
  references public.accounts (user_id, entity_id, id)
  on update cascade on delete cascade;

alter table public.planned_items
  drop constraint planned_items_user_id_budget_period_id_fkey,
  add constraint planned_items_user_entity_fkey
  foreign key (user_id, entity_id) references public.entities (user_id, id) on delete cascade,
  add constraint planned_items_user_entity_period_fkey
  foreign key (user_id, entity_id, budget_period_id)
  references public.budget_periods (user_id, entity_id, id) on delete cascade;

create index accounts_user_entity_idx on public.accounts (user_id, entity_id, is_active, display_order);
create index budget_periods_user_entity_idx on public.budget_periods (user_id, entity_id, starts_on desc);
create index budgets_user_entity_period_idx on public.budgets (user_id, entity_id, period_start);
create index transactions_user_entity_occurred_idx on public.transactions (user_id, entity_id, occurred_on desc, occurred_at desc);
create index planned_items_user_entity_period_idx on public.planned_items (user_id, entity_id, budget_period_id, direction, sort_order);

create function public.enforce_planned_item_account_entity()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.account_id is not null and not exists (
    select 1
    from public.accounts a
    where a.user_id = new.user_id
      and a.entity_id = new.entity_id
      and a.id = new.account_id
  ) then
    raise exception 'The selected account belongs to a different entity.';
  end if;
  return new;
end;
$$;

revoke all on function public.enforce_planned_item_account_entity() from public, anon, authenticated;

create trigger planned_items_account_entity_guard
before insert or update of user_id, entity_id, account_id on public.planned_items
for each row execute function public.enforce_planned_item_account_entity();

create function public.clear_old_entity_plan_account()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.entity_id is distinct from new.entity_id then
    update public.planned_items
    set account_id = null
    where user_id = new.user_id
      and account_id = new.id
      and entity_id <> new.entity_id;
  end if;
  return new;
end;
$$;

revoke all on function public.clear_old_entity_plan_account() from public, anon, authenticated;

create trigger accounts_clear_old_entity_plan_account
after update of entity_id on public.accounts
for each row execute function public.clear_old_entity_plan_account();

drop view public.budget_progress;
create view public.budget_progress
with (security_invoker = true)
as
select
  b.id,
  b.user_id,
  b.entity_id,
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
 and t.entity_id = b.entity_id
 and t.category_id = b.category_id
 and t.occurred_on >= b.period_start
 and t.occurred_on < (b.period_start + interval '1 month')::date
group by b.id;

revoke all on table public.budget_progress from anon;
grant select on table public.budget_progress to authenticated;

drop view public.planned_item_progress;
create view public.planned_item_progress
with (security_invoker = true)
as
select
  p.id,
  p.user_id,
  p.entity_id,
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

create or replace function public.bootstrap_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  personal_entity_id uuid := pg_catalog.gen_random_uuid();
begin
  insert into public.profiles (id, display_name)
  values (new.id, coalesce(split_part(new.email, '@', 1), ''));

  insert into public.entities (id, user_id, name, kind, is_default, display_order)
  values (personal_entity_id, new.id, 'Personal', 'personal', true, 0);

  insert into public.accounts (
    user_id, entity_id, institution, external_key, name, type, role, purpose, mask,
    include_in_safe_to_spend, display_order
  )
  values
    (new.id, personal_entity_id, 'absa', 'CHEQ1607', 'Cheque', 'cheque', 'operational', 'Primary income and known expenses', '1607', true, 1),
    (new.id, personal_entity_id, 'absa', 'credit-card-1', 'Credit card 1', 'credit_card', 'operational', 'Groceries and utilities', 'Add mask', true, 2),
    (new.id, personal_entity_id, 'absa', 'SAVE2959', 'Savings account', 'savings', 'operational', 'Gas and phone', '2959', true, 3),
    (new.id, personal_entity_id, 'absa', 'credit-card-2', 'Credit card 2', 'credit_card', 'operational', 'Surplus spending', 'Add mask', true, 4),
    (new.id, personal_entity_id, 'absa', 'notice-savings-32', '32 day notice savings', 'savings', 'savings', 'Primary savings account', 'Add mask', false, 5),
    (new.id, personal_entity_id, 'absa', 'home-loan-1', 'Home loan 1', 'home_loan', 'liability', 'Mortgage for first property', 'Add mask', false, 6),
    (new.id, personal_entity_id, 'absa', 'home-loan-2', 'Home loan 2', 'home_loan', 'liability', 'Mortgage for second property', 'Add mask', false, 7),
    (new.id, personal_entity_id, 'absa', 'absa-rewards', 'Absa Rewards', 'rewards', 'rewards', 'Rewards balance', 'Rewards', false, 8);

  insert into public.categories (user_id, name, colour, icon, system_key, sort_order)
  values
    (new.id, 'Groceries', '#D9E8C4', 'shopping-basket', 'groceries', 1),
    (new.id, 'Utilities', '#C9DDF2', 'lightbulb', 'utilities', 2),
    (new.id, 'Gas', '#F4D7A1', 'flame', 'gas', 3),
    (new.id, 'Phone', '#D8D0EF', 'smartphone', 'phone', 4),
    (new.id, 'Savings', '#BFE3D0', 'piggy-bank', 'savings', 5),
    (new.id, 'Debt', '#F3C3C3', 'landmark', 'debt', 6),
    (new.id, 'Insurance', '#C9E3E8', 'shield', 'insurance', 7),
    (new.id, 'Home', '#E2D4C2', 'house', 'home', 8),
    (new.id, 'Transport', '#D4DEB8', 'car', 'transport', 9),
    (new.id, 'Education', '#D7C9EA', 'book-open', 'education', 10),
    (new.id, 'Entertainment', '#F0C9DC', 'sparkles', 'entertainment', 11),
    (new.id, 'Domestic help', '#E8D6BA', 'heart-handshake', 'domestic-help', 12),
    (new.id, 'Travel', '#BEDDE5', 'plane', 'travel', 13),
    (new.id, 'Other', '#E8E7E0', 'circle', 'other', 14);

  return new;
end;
$$;

revoke all on function public.bootstrap_new_user() from public, anon, authenticated;
