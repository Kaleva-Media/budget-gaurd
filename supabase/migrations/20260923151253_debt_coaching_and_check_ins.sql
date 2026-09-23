alter table public.debts
  add column closed_reason text check (closed_reason in ('paid_off', 'archived')),
  add column closed_at date,
  add constraint debts_closed_state_check check (
    (is_active and closed_reason is null and closed_at is null)
    or
    (not is_active and closed_reason is distinct from null and closed_at is distinct from null)
  ),
  add constraint debts_user_entity_id_key unique (user_id, entity_id, id);

alter table public.debt_preferences
  add column reminder_enabled boolean default false,
  add column reminder_day integer default 28 check (reminder_day between 1 and 28);

create table public.debt_check_ins (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  entity_id uuid not null,
  check_in_month date not null check (check_in_month = date_trunc('month', check_in_month)::date),
  recorded_on date not null default current_date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint debt_check_ins_user_entity_fkey
    foreign key (user_id, entity_id)
    references public.entities (user_id, id)
    on delete cascade,
  constraint debt_check_ins_user_entity_month_key unique (user_id, entity_id, check_in_month),
  constraint debt_check_ins_user_entity_id_key unique (user_id, entity_id, id)
);

create table public.debt_check_in_balances (
  check_in_id uuid not null,
  user_id uuid not null references auth.users (id) on delete cascade,
  entity_id uuid not null,
  debt_id uuid not null,
  balance_cents bigint not null check (balance_cents >= 0),
  annual_interest_bps integer not null check (annual_interest_bps between 0 and 100000),
  minimum_payment_cents bigint not null check (minimum_payment_cents > 0),
  in_arrears boolean not null default false,
  created_at timestamptz not null default now(),
  primary key (check_in_id, debt_id),
  constraint debt_check_in_balances_check_in_fkey
    foreign key (user_id, entity_id, check_in_id)
    references public.debt_check_ins (user_id, entity_id, id)
    on delete cascade,
  constraint debt_check_in_balances_debt_fkey
    foreign key (user_id, entity_id, debt_id)
    references public.debts (user_id, entity_id, id)
    on delete restrict
);

create index debt_check_ins_user_entity_month_idx
  on public.debt_check_ins (user_id, entity_id, check_in_month desc);
create index debt_check_in_balances_debt_idx
  on public.debt_check_in_balances (user_id, entity_id, debt_id, check_in_id);

create trigger debt_check_ins_set_updated_at before update on public.debt_check_ins
for each row execute function public.set_updated_at();

alter table public.debt_check_ins enable row level security;
alter table public.debt_check_in_balances enable row level security;

revoke all on table public.debt_check_ins, public.debt_check_in_balances from anon;
grant select, insert, update, delete on table public.debt_check_ins, public.debt_check_in_balances to authenticated;

create policy "debt_check_ins_select_own" on public.debt_check_ins for select to authenticated
using ((select auth.uid()) = user_id);
create policy "debt_check_ins_insert_own_entity" on public.debt_check_ins for insert to authenticated
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1 from public.entities e
    where e.id = entity_id and e.user_id = (select auth.uid())
  )
);
create policy "debt_check_ins_update_own" on public.debt_check_ins for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);
create policy "debt_check_ins_delete_own" on public.debt_check_ins for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "debt_check_in_balances_select_own" on public.debt_check_in_balances for select to authenticated
using ((select auth.uid()) = user_id);
create policy "debt_check_in_balances_insert_own" on public.debt_check_in_balances for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "debt_check_in_balances_update_own" on public.debt_check_in_balances for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);
create policy "debt_check_in_balances_delete_own" on public.debt_check_in_balances for delete to authenticated
using ((select auth.uid()) = user_id);

create function public.record_debt_check_in(
  p_entity_id uuid,
  p_check_in_month date,
  p_recorded_on date,
  p_balances jsonb
)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
  v_check_in_id uuid;
  v_expected_count integer;
  v_received_count integer;
  v_item jsonb;
begin
  if v_user_id is null then
    raise exception 'Authentication is required.' using errcode = '42501';
  end if;
  if p_check_in_month is null or p_check_in_month <> date_trunc('month', p_check_in_month)::date then
    raise exception 'Check-in month must be the first day of a month.' using errcode = '22023';
  end if;
  if p_recorded_on is null then
    raise exception 'Recorded date is required.' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.entities e
    where e.id = p_entity_id and e.user_id = v_user_id
  ) then
    raise exception 'Entity was not found.' using errcode = '42501';
  end if;
  if jsonb_typeof(p_balances) <> 'array' then
    raise exception 'Balances must be a JSON array.' using errcode = '22023';
  end if;

  insert into public.debt_check_ins (user_id, entity_id, check_in_month, recorded_on)
  values (v_user_id, p_entity_id, p_check_in_month, p_recorded_on)
  on conflict (user_id, entity_id, check_in_month)
  do update set recorded_on = excluded.recorded_on, updated_at = now()
  returning id into v_check_in_id;

  perform 1
  from public.debts d
  where d.user_id = v_user_id
    and d.entity_id = p_entity_id
    and (
      d.is_active
      or exists (
        select 1 from public.debt_check_in_balances b
        where b.check_in_id = v_check_in_id and b.debt_id = d.id
      )
    )
  for update;

  select count(*) into v_expected_count
  from public.debts d
  where d.user_id = v_user_id
    and d.entity_id = p_entity_id
    and (
      d.is_active
      or exists (
        select 1 from public.debt_check_in_balances b
        where b.check_in_id = v_check_in_id and b.debt_id = d.id
      )
    );

  select count(*), count(distinct value->>'debt_id')
  into v_received_count, v_expected_count
  from jsonb_array_elements(p_balances);
  if v_received_count <> v_expected_count then
    raise exception 'Each debt must appear exactly once.' using errcode = '22023';
  end if;

  select count(*) into v_expected_count
  from public.debts d
  where d.user_id = v_user_id
    and d.entity_id = p_entity_id
    and (
      d.is_active
      or exists (
        select 1 from public.debt_check_in_balances b
        where b.check_in_id = v_check_in_id and b.debt_id = d.id
      )
    );
  if v_received_count <> v_expected_count then
    raise exception 'Include every active debt in the check-in.' using errcode = '22023';
  end if;

  if exists (
    select 1
    from jsonb_array_elements(p_balances) item
    left join public.debts d
      on d.id = (item->>'debt_id')::uuid
     and d.user_id = v_user_id
     and d.entity_id = p_entity_id
     and (d.is_active or exists (
       select 1 from public.debt_check_in_balances b
       where b.check_in_id = v_check_in_id and b.debt_id = d.id
     ))
    where d.id is null
      or not (item ? 'balance_cents')
      or not (item ? 'annual_interest_bps')
      or not (item ? 'minimum_payment_cents')
      or (item->>'balance_cents')::bigint < 0
      or (item->>'annual_interest_bps')::integer not between 0 and 100000
      or (item->>'minimum_payment_cents')::bigint <= 0
  ) then
    raise exception 'One or more debt values are invalid.' using errcode = '22023';
  end if;

  for v_item in select value from jsonb_array_elements(p_balances)
  loop
    insert into public.debt_check_in_balances (
      check_in_id, user_id, entity_id, debt_id, balance_cents,
      annual_interest_bps, minimum_payment_cents, in_arrears
    ) values (
      v_check_in_id,
      v_user_id,
      p_entity_id,
      (v_item->>'debt_id')::uuid,
      (v_item->>'balance_cents')::bigint,
      (v_item->>'annual_interest_bps')::integer,
      (v_item->>'minimum_payment_cents')::bigint,
      coalesce((v_item->>'in_arrears')::boolean, false)
    )
    on conflict (check_in_id, debt_id)
    do update set
      balance_cents = excluded.balance_cents,
      annual_interest_bps = excluded.annual_interest_bps,
      minimum_payment_cents = excluded.minimum_payment_cents,
      in_arrears = excluded.in_arrears;

    update public.debts
    set balance_cents = case
          when (v_item->>'balance_cents')::bigint = 0 then balance_cents
          else (v_item->>'balance_cents')::bigint
        end,
        annual_interest_bps = (v_item->>'annual_interest_bps')::integer,
        minimum_payment_cents = (v_item->>'minimum_payment_cents')::bigint,
        in_arrears = coalesce((v_item->>'in_arrears')::boolean, false),
        is_active = (v_item->>'balance_cents')::bigint > 0,
        closed_reason = case when (v_item->>'balance_cents')::bigint = 0 then 'paid_off' else null end,
        closed_at = case when (v_item->>'balance_cents')::bigint = 0 then p_recorded_on else null end
    where id = (v_item->>'debt_id')::uuid
      and user_id = v_user_id
      and entity_id = p_entity_id;
  end loop;

  return v_check_in_id;
end;
$$;

revoke all on function public.record_debt_check_in(uuid, date, date, jsonb) from public, anon;
grant execute on function public.record_debt_check_in(uuid, date, date, jsonb) to authenticated;

create view public.debt_spending_history
with (security_invoker = true)
as
select
  t.user_id,
  t.entity_id,
  date_trunc('month', t.occurred_on)::date as spend_month,
  t.category_id,
  c.name as category_name,
  c.system_key,
  sum(abs(t.amount_cents))::bigint as spent_cents
from public.transactions t
left join public.categories c
  on c.user_id = t.user_id and c.id = t.category_id
where t.status = 'posted'
  and t.amount_cents < 0
  and t.kind not in ('transfer', 'reversal')
group by t.user_id, t.entity_id, date_trunc('month', t.occurred_on)::date,
  t.category_id, c.name, c.system_key;

revoke all on table public.debt_spending_history from public, anon;
grant select on table public.debt_spending_history to authenticated;
