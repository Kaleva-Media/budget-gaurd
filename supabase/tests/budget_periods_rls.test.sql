begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.budget_periods'::regclass), true, 'budget_periods has RLS enabled');
select * from finish();
rollback;
