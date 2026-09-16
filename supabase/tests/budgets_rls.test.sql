begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.budgets'::regclass), true, 'budgets has RLS enabled');
select * from finish();
rollback;
