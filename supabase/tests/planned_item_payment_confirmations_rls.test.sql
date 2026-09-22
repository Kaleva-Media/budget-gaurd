-- Synthetic fixtures only. The transaction rolls everything back.
begin;
select plan(7);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, created_at, updated_at)
values
  ('61000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'paid-one@example.test', '', now(), now()),
  ('61000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'paid-two@example.test', '', now(), now());

insert into public.budget_periods (id, user_id, entity_id, starts_on, status)
values
  (
    '62000000-0000-4000-8000-000000000001',
    '61000000-0000-4000-8000-000000000001',
    (select id from public.entities where user_id = '61000000-0000-4000-8000-000000000001' and is_default),
    '2026-09-01',
    'active'
  ),
  (
    '62000000-0000-4000-8000-000000000002',
    '61000000-0000-4000-8000-000000000002',
    (select id from public.entities where user_id = '61000000-0000-4000-8000-000000000002' and is_default),
    '2026-09-01',
    'active'
  );

insert into public.planned_items (id, user_id, entity_id, budget_period_id, direction, kind, name, planned_cents)
values
  (
    '63000000-0000-4000-8000-000000000001',
    '61000000-0000-4000-8000-000000000001',
    (select id from public.entities where user_id = '61000000-0000-4000-8000-000000000001' and is_default),
    '62000000-0000-4000-8000-000000000001',
    'expense', 'fixed_expense', 'Synthetic rent', 10000
  ),
  (
    '63000000-0000-4000-8000-000000000002',
    '61000000-0000-4000-8000-000000000001',
    (select id from public.entities where user_id = '61000000-0000-4000-8000-000000000001' and is_default),
    '62000000-0000-4000-8000-000000000001',
    'income', 'income', 'Synthetic salary', 50000
  ),
  (
    '63000000-0000-4000-8000-000000000003',
    '61000000-0000-4000-8000-000000000002',
    (select id from public.entities where user_id = '61000000-0000-4000-8000-000000000002' and is_default),
    '62000000-0000-4000-8000-000000000002',
    'expense', 'fixed_expense', 'Other owner rent', 20000
  );

insert into public.planned_item_payment_confirmations (planned_item_id, user_id)
values ('63000000-0000-4000-8000-000000000003', '61000000-0000-4000-8000-000000000002');

select is(
  (select relrowsecurity from pg_class where oid = 'public.planned_item_payment_confirmations'::regclass),
  true,
  'payment confirmations have RLS enabled'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '61000000-0000-4000-8000-000000000001', true);

select lives_ok(
  $$insert into public.planned_item_payment_confirmations (planned_item_id, user_id)
    values ('63000000-0000-4000-8000-000000000001', '61000000-0000-4000-8000-000000000001')$$,
  'an owner can mark an expense paid'
);
select is(
  (select count(*)::integer from public.planned_item_payment_confirmations),
  1,
  'an owner sees only their own payment confirmation'
);
select throws_ok(
  $$insert into public.planned_item_payment_confirmations (planned_item_id, user_id)
    values ('63000000-0000-4000-8000-000000000002', '61000000-0000-4000-8000-000000000001')$$,
  '42501',
  null,
  'income cannot be marked as a paid expense'
);
select throws_ok(
  $$insert into public.planned_item_payment_confirmations (planned_item_id, user_id)
    values ('63000000-0000-4000-8000-000000000003', '61000000-0000-4000-8000-000000000002')$$,
  '42501',
  null,
  'one user cannot confirm another user expense'
);
select lives_ok(
  $$delete from public.planned_item_payment_confirmations
    where planned_item_id = '63000000-0000-4000-8000-000000000001'$$,
  'an owner can undo a manual paid confirmation'
);

reset role;
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select throws_ok(
  'select * from public.planned_item_payment_confirmations',
  '42501',
  null,
  'anonymous users cannot read payment confirmations'
);
reset role;

select * from finish();
rollback;
