-- Synthetic fixtures only. The transaction rolls everything back.
begin;
select plan(21);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, created_at, updated_at)
values
  ('73000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'coach-one@example.test', '', now(), now()),
  ('73000000-0000-4000-8000-000000000002', '00000000-0000-0000-8000-000000000000', 'authenticated', 'authenticated', 'coach-two@example.test', '', now(), now());

select is((select relrowsecurity from pg_class where oid = 'public.debt_check_ins'::regclass), true, 'check-ins have RLS');
select is((select relrowsecurity from pg_class where oid = 'public.debt_check_in_balances'::regclass), true, 'check-in balances have RLS');
select is(
  (select reloptions @> array['security_invoker=true'] from pg_class where oid = 'public.debt_spending_history'::regclass),
  true,
  'spending history executes with invoker security'
);
select ok(has_function_privilege('authenticated', 'public.record_debt_check_in(uuid,date,date,jsonb)', 'execute'), 'authenticated can call check-in RPC');
select ok(not has_function_privilege('anon', 'public.record_debt_check_in(uuid,date,date,jsonb)', 'execute'), 'anonymous cannot call check-in RPC');
select ok(has_table_privilege('authenticated', 'public.debt_spending_history', 'select'), 'authenticated can read spending history');
select ok(not has_table_privilege('anon', 'public.debt_spending_history', 'select'), 'anonymous cannot read spending history');

set local role authenticated;
select set_config('request.jwt.claim.sub', '73000000-0000-4000-8000-000000000001', true);

insert into public.debts (
  id, user_id, entity_id, name, debt_type, balance_cents,
  annual_interest_bps, minimum_payment_cents
) values
  (
    '74000000-0000-4000-8000-000000000001',
    '73000000-0000-4000-8000-000000000001',
    (select id from public.entities where user_id = '73000000-0000-4000-8000-000000000001' and is_default),
    'Synthetic card', 'credit_card', 100000, 2400, 5000
  ),
  (
    '74000000-0000-4000-8000-000000000002',
    '73000000-0000-4000-8000-000000000001',
    (select id from public.entities where user_id = '73000000-0000-4000-8000-000000000001' and is_default),
    'Synthetic loan', 'personal_loan', 200000, 1200, 10000
  );

select throws_ok(
  $$select public.record_debt_check_in(
    (select id from public.entities where user_id = '73000000-0000-4000-8000-000000000001' and is_default),
    '2026-09-01', '2026-09-28',
    '[{"debt_id":"74000000-0000-4000-8000-000000000001","balance_cents":90000,"annual_interest_bps":2400,"minimum_payment_cents":5000,"in_arrears":false}]'::jsonb
  )$$,
  '22023',
  null,
  'a check-in must include every active debt'
);

select lives_ok(
  $$select public.record_debt_check_in(
    (select id from public.entities where user_id = '73000000-0000-4000-8000-000000000001' and is_default),
    '2026-09-01', '2026-09-28',
    '[
      {"debt_id":"74000000-0000-4000-8000-000000000001","balance_cents":0,"annual_interest_bps":2400,"minimum_payment_cents":5000,"in_arrears":false},
      {"debt_id":"74000000-0000-4000-8000-000000000002","balance_cents":185000,"annual_interest_bps":1200,"minimum_payment_cents":10000,"in_arrears":false}
    ]'::jsonb
  )$$,
  'an owner can record an atomic check-in'
);
select is((select count(*)::integer from public.debt_check_ins), 1, 'one monthly check-in is stored');
select is((select count(*)::integer from public.debt_check_in_balances), 2, 'all debt snapshots are stored');
select is((select is_active from public.debts where id = '74000000-0000-4000-8000-000000000001'), false, 'zero balance closes a debt');
select is((select closed_reason from public.debts where id = '74000000-0000-4000-8000-000000000001'), 'paid_off', 'closed debt is retained as paid off');

select lives_ok(
  $$select public.record_debt_check_in(
    (select id from public.entities where user_id = '73000000-0000-4000-8000-000000000001' and is_default),
    '2026-09-01', '2026-09-29',
    '[
      {"debt_id":"74000000-0000-4000-8000-000000000001","balance_cents":5000,"annual_interest_bps":2400,"minimum_payment_cents":5000,"in_arrears":false},
      {"debt_id":"74000000-0000-4000-8000-000000000002","balance_cents":180000,"annual_interest_bps":1200,"minimum_payment_cents":10000,"in_arrears":false}
    ]'::jsonb
  )$$,
  'the same month can be corrected including a just-paid debt'
);
select is((select count(*)::integer from public.debt_check_ins), 1, 'same-month correction updates instead of duplicating');
select is((select balance_cents from public.debts where id = '74000000-0000-4000-8000-000000000001'), 5000::bigint, 'correction restores the revised balance');
select is((select is_active from public.debts where id = '74000000-0000-4000-8000-000000000001'), true, 'correction reopens a nonzero debt');

select set_config('request.jwt.claim.sub', '73000000-0000-4000-8000-000000000002', true);
select is((select count(*)::integer from public.debt_check_ins), 0, 'another owner cannot read check-ins');
select is((select count(*)::integer from public.debt_check_in_balances), 0, 'another owner cannot read snapshots');

reset role;
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok('select * from public.debt_check_ins', '42501', null, 'anonymous cannot read check-ins');
select throws_ok('select * from public.debt_spending_history', '42501', null, 'anonymous cannot read spending history');
reset role;

select * from finish();
rollback;
