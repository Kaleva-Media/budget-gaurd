create function public.assign_legacy_entity_scope()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.entity_id is not null then
    return new;
  end if;

  if tg_table_name = 'transactions' then
    select a.entity_id into new.entity_id
    from public.accounts a
    where a.user_id = new.user_id and a.id = new.account_id;
  elsif tg_table_name = 'planned_items' then
    select p.entity_id into new.entity_id
    from public.budget_periods p
    where p.user_id = new.user_id and p.id = new.budget_period_id;
  else
    select e.id into new.entity_id
    from public.entities e
    where e.user_id = new.user_id and e.is_default and e.is_active;
  end if;

  if new.entity_id is null then
    raise exception 'No entity could be resolved for this record.';
  end if;

  return new;
end;
$$;

revoke all on function public.assign_legacy_entity_scope() from public, anon, authenticated;

create trigger accounts_assign_legacy_entity
before insert on public.accounts
for each row execute function public.assign_legacy_entity_scope();

create trigger budget_periods_assign_legacy_entity
before insert on public.budget_periods
for each row execute function public.assign_legacy_entity_scope();

create trigger budgets_assign_legacy_entity
before insert on public.budgets
for each row execute function public.assign_legacy_entity_scope();

create trigger transactions_assign_legacy_entity
before insert on public.transactions
for each row execute function public.assign_legacy_entity_scope();

create trigger planned_items_00_assign_legacy_entity
before insert on public.planned_items
for each row execute function public.assign_legacy_entity_scope();
