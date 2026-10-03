alter table public.entities
  add column budget_cycle_day smallint default 1
  check (budget_cycle_day between 1 and 28);

create view public.budget_cycle_progress
with (security_invoker = true)
as
select
  b.id,
  b.user_id,
  b.entity_id,
  b.category_id,
  b.period_start,
  b.limit_cents,
  bounds.cycle_starts_on,
  bounds.cycle_ends_on,
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
join public.entities e
  on e.user_id = b.user_id
 and e.id = b.entity_id
cross join lateral (
  select
    case
      when coalesce(e.budget_cycle_day, 1) = 1 then b.period_start
      else (b.period_start - interval '1 month'
        + make_interval(days => coalesce(e.budget_cycle_day, 1) - 1))::date
    end as cycle_starts_on,
    case
      when coalesce(e.budget_cycle_day, 1) = 1 then (b.period_start + interval '1 month')::date
      else (b.period_start
        + make_interval(days => coalesce(e.budget_cycle_day, 1) - 1))::date
    end as cycle_ends_on
) bounds
left join public.transactions t
  on t.user_id = b.user_id
 and t.entity_id = b.entity_id
 and t.category_id = b.category_id
 and t.occurred_on >= bounds.cycle_starts_on
 and t.occurred_on < bounds.cycle_ends_on
group by
  b.id,
  b.user_id,
  b.entity_id,
  b.category_id,
  b.period_start,
  b.limit_cents,
  bounds.cycle_starts_on,
  bounds.cycle_ends_on;

revoke all on table public.budget_cycle_progress from anon;
grant select on table public.budget_cycle_progress to authenticated;
