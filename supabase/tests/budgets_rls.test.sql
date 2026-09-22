begin;
select plan(8);
select is((select relrowsecurity from pg_class where oid = 'public.budgets'::regclass), true, 'budgets has RLS enabled');

insert into auth.users(id,email) values
('11111111-1111-4111-8111-111111111111','budget-owner@example.invalid'),
('22222222-2222-4222-8222-222222222222','other-budget-owner@example.invalid');

insert into public.entities(id,user_id,name,kind) values
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','11111111-1111-4111-8111-111111111111','Budget owner entity','personal'),
('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','22222222-2222-4222-8222-222222222222','Other budget entity','personal');

insert into public.categories(id,user_id,name,system_key,sort_order,category_scope) values
('aaaaaaaa-0000-4000-8000-000000000101','11111111-1111-4111-8111-111111111111','Owner flexible category','owner-flexible',100,'personal'),
('bbbbbbbb-0000-4000-8000-000000000101','22222222-2222-4222-8222-222222222222','Other flexible category','other-flexible',100,'personal');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}',true);

select lives_ok(
  $$insert into public.budgets(user_id,entity_id,category_id,period_start,limit_cents)
    values ('11111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','aaaaaaaa-0000-4000-8000-000000000101','2030-01-01',10000)$$,
  'owner can create a flexible budget limit'
);
select is(
  (select limit_cents from public.budgets where category_id='aaaaaaaa-0000-4000-8000-000000000101' and period_start='2030-01-01'),
  10000::bigint,
  'owner can read the created limit'
);
select lives_ok(
  $$update public.budgets set limit_cents=25000 where category_id='aaaaaaaa-0000-4000-8000-000000000101' and period_start='2030-01-01'$$,
  'owner can update a flexible budget limit'
);
select is(
  (select limit_cents from public.budgets where category_id='aaaaaaaa-0000-4000-8000-000000000101' and period_start='2030-01-01'),
  25000::bigint,
  'updated limit is visible to its owner'
);
select throws_ok(
  $$insert into public.budgets(user_id,entity_id,category_id,period_start,limit_cents)
    values ('22222222-2222-4222-8222-222222222222','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','bbbbbbbb-0000-4000-8000-000000000101','2030-01-01',10000)$$,
  '42501',
  null,
  'owner cannot create another user flexible budget limit'
);
select lives_ok(
  $$delete from public.budgets where category_id='aaaaaaaa-0000-4000-8000-000000000101' and period_start='2030-01-01'$$,
  'owner can remove a flexible budget limit'
);
select is(
  (select count(*) from public.budgets where category_id='aaaaaaaa-0000-4000-8000-000000000101' and period_start='2030-01-01'),
  0::bigint,
  'removed limit is no longer visible'
);

reset role;
select * from finish();
rollback;
