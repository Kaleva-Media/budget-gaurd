begin;
select plan(12);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, created_at, updated_at)
values
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'move-one@example.test', '', now(), now()),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'move-two@example.test', '', now(), now());

insert into public.entities (id, user_id, name, kind, display_order)
values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Test Company', 'company', 1);

insert into public.budget_periods (id, user_id, entity_id, starts_on, status)
values
  (
    '30000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000001' and is_default),
    '2026-09-01',
    'active'
  ),
  (
    '30000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000001',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000001' and is_default),
    '2026-10-01',
    'draft'
  ),
  ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '2026-09-01', 'active'),
  (
    '30000000-0000-0000-0000-000000000004',
    '10000000-0000-0000-0000-000000000002',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000002' and is_default),
    '2026-09-01',
    'active'
  );

insert into public.planned_items (
  id, user_id, entity_id, budget_period_id, direction, kind, name, planned_cents, account_id, category_id
)
values
  (
    '40000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000001' and is_default),
    '30000000-0000-0000-0000-000000000001',
    'expense', 'fixed_expense', 'Move me', 10000,
    (select id from public.accounts where user_id = '10000000-0000-0000-0000-000000000001' order by display_order limit 1),
    (select id from public.categories where user_id = '10000000-0000-0000-0000-000000000001' and category_scope = 'personal' order by sort_order limit 1)
  ),
  (
    '40000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000002',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000002' and is_default),
    '30000000-0000-0000-0000-000000000004',
    'income', 'income', 'Someone else''s entry', 20000, null, null
  ),
  (
    '40000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000001',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000001' and is_default),
    '30000000-0000-0000-0000-000000000001',
    'expense', 'variable_expense', 'Matched entry', 30000, null, null
  );

insert into public.transactions (
  id, user_id, entity_id, account_id, occurred_on, amount_cents, status, kind, source, source_fingerprint
)
values (
  '50000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000001',
  (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000001' and is_default),
  (select id from public.accounts where user_id = '10000000-0000-0000-0000-000000000001' order by display_order limit 1),
  '2026-09-10', -30000, 'posted', 'card_purchase', 'manual', repeat('a', 64)
);

insert into public.planned_item_matches (user_id, planned_item_id, transaction_id, amount_cents)
values (
  '10000000-0000-0000-0000-000000000001',
  '40000000-0000-0000-0000-000000000003',
  '50000000-0000-0000-0000-000000000001',
  30000
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '10000000-0000-0000-0000-000000000001', true);

select lives_ok(
  $$select public.move_planned_item('40000000-0000-0000-0000-000000000001',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000001' and is_default),
    '30000000-0000-0000-0000-000000000002')$$,
  'an owner can move an entry to another month'
);
select is((select budget_period_id from public.planned_items where id = '40000000-0000-0000-0000-000000000001'), '30000000-0000-0000-0000-000000000002'::uuid, 'the destination month is stored');
select ok((select account_id is not null from public.planned_items where id = '40000000-0000-0000-0000-000000000001'), 'same-entity moves retain the account');
select ok((select category_id is not null from public.planned_items where id = '40000000-0000-0000-0000-000000000001'), 'same-entity moves retain the category');

select lives_ok(
  $$select public.move_planned_item('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000003')$$,
  'an owner can move an entry to another entity'
);
select is((select entity_id from public.planned_items where id = '40000000-0000-0000-0000-000000000001'), '20000000-0000-0000-0000-000000000001'::uuid, 'the destination entity is stored');
select is((select account_id from public.planned_items where id = '40000000-0000-0000-0000-000000000001'), null, 'cross-entity moves clear the old account');
select is((select category_id from public.planned_items where id = '40000000-0000-0000-0000-000000000001'), null, 'personal categories are cleared when moving to a business');

select throws_ok(
  $$select public.move_planned_item('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000003')$$,
  'P0001',
  'Planned item not found.',
  'one user cannot move another user''s entry'
);

select throws_ok(
  $$select public.move_planned_item('40000000-0000-0000-0000-000000000003',
    (select id from public.entities where user_id = '10000000-0000-0000-0000-000000000001' and is_default),
    '30000000-0000-0000-0000-000000000002')$$,
  'P0001',
  'Entries with matched payments cannot be moved.',
  'an entry with matched payments cannot be moved'
);
select is(
  (select budget_period_id from public.planned_items where id = '40000000-0000-0000-0000-000000000003'),
  '30000000-0000-0000-0000-000000000001'::uuid,
  'a refused matched entry stays in its original period'
);

select is(
  (select count(*)::integer from public.categories where user_id = '10000000-0000-0000-0000-000000000001' and category_scope = 'business'),
  14,
  'business expense types are bootstrapped separately'
);

select * from finish();
rollback;
