-- Synthetic fixtures only. The transaction rolls everything back.
begin;
select plan(9);

select has_column('public', 'entities', 'budget_cycle_day', 'workspaces store a budget cycle day');
select is(
  (select reloptions @> array['security_invoker=true'] from pg_class where oid = 'public.budget_cycle_progress'::regclass),
  true,
  'cycle progress is a security-invoker view'
);

insert into auth.users(id,email) values
  ('71000000-0000-4000-8000-000000000001','cycle-owner@example.invalid'),
  ('71000000-0000-4000-8000-000000000002','cycle-other@example.invalid');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"71000000-0000-4000-8000-000000000001","role":"authenticated"}',true);

update public.entities
set budget_cycle_day = 28
where user_id = '71000000-0000-4000-8000-000000000001'
  and is_default;

select is(
  (select budget_cycle_day from public.entities where is_default),
  28::smallint,
  'an owner can select day 28 for the active workspace'
);

select throws_ok(
  $$update public.entities set budget_cycle_day = 29 where is_default$$,
  '23514',
  null,
  'cycle days after 28 are rejected so every monthly boundary exists'
);

insert into public.budget_periods (id,user_id,entity_id,starts_on,status)
select
  '72000000-0000-4000-8000-000000000001',
  user_id,
  id,
  '2026-09-01',
  'active'
from public.entities
where user_id = '71000000-0000-4000-8000-000000000001'
  and is_default;

insert into public.budgets (id,user_id,entity_id,category_id,period_start,limit_cents)
select
  '73000000-0000-4000-8000-000000000001',
  e.user_id,
  e.id,
  c.id,
  '2026-09-01',
  100000
from public.entities e
join public.categories c on c.user_id = e.user_id
where e.user_id = '71000000-0000-4000-8000-000000000001'
  and e.is_default
order by c.sort_order
limit 1;

insert into public.transactions (
  user_id,entity_id,account_id,category_id,occurred_on,amount_cents,status,kind,source,source_fingerprint
)
select
  e.user_id,
  e.id,
  a.id,
  b.category_id,
  fixture.occurred_on,
  fixture.amount_cents,
  'posted',
  'card_purchase',
  'manual',
  repeat(fixture.fingerprint_character, 64)
from public.entities e
join public.accounts a on a.user_id = e.user_id and a.entity_id = e.id
join public.budgets b on b.user_id = e.user_id and b.entity_id = e.id
cross join (values
  ('2026-08-27'::date, -1000::bigint, 'a'),
  ('2026-08-28'::date, -2000::bigint, 'b'),
  ('2026-09-27'::date, -3000::bigint, 'c'),
  ('2026-09-28'::date, -4000::bigint, 'd')
) fixture(occurred_on,amount_cents,fingerprint_character)
where e.user_id = '71000000-0000-4000-8000-000000000001'
  and e.is_default
  and b.id = '73000000-0000-4000-8000-000000000001'
  and a.id = (
    select first_account.id
    from public.accounts first_account
    where first_account.user_id = e.user_id
      and first_account.entity_id = e.id
    order by first_account.display_order, first_account.id
    limit 1
  );

select is(
  (select cycle_starts_on from public.budget_cycle_progress where id = '73000000-0000-4000-8000-000000000001'),
  '2026-08-28'::date,
  'the September cycle starts on 28 August'
);
select is(
  (select cycle_ends_on from public.budget_cycle_progress where id = '73000000-0000-4000-8000-000000000001'),
  '2026-09-28'::date,
  'the September cycle ends before 28 September'
);
select is(
  (select spent_cents from public.budget_cycle_progress where id = '73000000-0000-4000-8000-000000000001'),
  5000::bigint,
  'only transactions from 28 August through 27 September count'
);

update public.entities
set budget_cycle_day = 14
where user_id = '71000000-0000-4000-8000-000000000002';

reset role;
select is(
  (select budget_cycle_day from public.entities where user_id = '71000000-0000-4000-8000-000000000002' and is_default),
  1::smallint,
  'one owner cannot change another owner cycle day'
);

set local role anon;
select set_config('request.jwt.claims','{"role":"anon"}',true);
select throws_ok(
  'select * from public.budget_cycle_progress',
  '42501',
  null,
  'anonymous users cannot read cycle spending'
);

reset role;
select * from finish();
rollback;
