begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.planned_items'::regclass), true, 'planned_items has RLS enabled');
select * from finish();
rollback;
