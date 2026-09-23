-- Synthetic fixtures only. The transaction rolls everything back.
begin;
select plan(14);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, created_at, updated_at)
values
  ('71000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'debt-one@example.test', '', now(), now()),
  ('71000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'debt-two@example.test', '', now(), now());

select is(
  (select relrowsecurity from pg_class where oid = 'public.debts'::regclass),
  true,
  'debts have RLS enabled'
);
select is(
  (select relrowsecurity from pg_class where oid = 'public.debt_preferences'::regclass),
  true,
  'debt preferences have RLS enabled'
);
select ok(
  has_table_privilege('authenticated', 'public.debts', 'select,insert,update,delete'),
  'authenticated users receive debt CRUD grants'
);
select ok(
  has_table_privilege('authenticated', 'public.debt_preferences', 'select,insert,update,delete'),
  'authenticated users receive preference CRUD grants'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '71000000-0000-4000-8000-000000000001', true);

select lives_ok(
  $$insert into public.debts (
      id, user_id, entity_id, name, debt_type, balance_cents,
      annual_interest_bps, minimum_payment_cents
    ) values (
      '72000000-0000-4000-8000-000000000001',
      '71000000-0000-4000-8000-000000000001',
      (select id from public.entities where user_id = '71000000-0000-4000-8000-000000000001' and is_default),
      'Synthetic card', 'credit_card', 100000, 2400, 5000
    )$$,
  'an owner can add a debt to their entity'
);
select lives_ok(
  $$insert into public.debt_preferences (
      user_id, entity_id, goal, consolidation_apr_bps, consolidation_term_months
    ) values (
      '71000000-0000-4000-8000-000000000001',
      (select id from public.entities where user_id = '71000000-0000-4000-8000-000000000001' and is_default),
      'balanced', 1200, 24
    )$$,
  'an owner can save strategy preferences'
);
select is((select count(*)::integer from public.debts), 1, 'an owner sees their own debts');
select lives_ok(
  $$update public.debts
    set balance_cents = 90000
    where id = '72000000-0000-4000-8000-000000000001'$$,
  'an owner can update their debt'
);
select lives_ok(
  $$update public.debt_preferences
    set goal = 'lowest_cost'
    where user_id = '71000000-0000-4000-8000-000000000001'$$,
  'an owner can update strategy preferences'
);
select throws_ok(
  $$insert into public.debts (
      user_id, entity_id, name, debt_type, balance_cents,
      annual_interest_bps, minimum_payment_cents
    ) values (
      '71000000-0000-4000-8000-000000000001',
      (select id from public.entities where user_id = '71000000-0000-4000-8000-000000000002' and is_default),
      'Cross-owner debt', 'other', 100000, 1000, 5000
    )$$,
  '42501',
  null,
  'an owner cannot add debt to another user entity'
);

select set_config('request.jwt.claim.sub', '71000000-0000-4000-8000-000000000002', true);
select is((select count(*)::integer from public.debts), 0, 'another owner cannot read the debt');
select is((select count(*)::integer from public.debt_preferences), 0, 'another owner cannot read strategy preferences');

reset role;
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok('select * from public.debts', '42501', null, 'anonymous users cannot read debts');
select throws_ok('select * from public.debt_preferences', '42501', null, 'anonymous users cannot read strategy preferences');
reset role;

select * from finish();
rollback;
