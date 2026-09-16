begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.categories'::regclass), true, 'categories has RLS enabled');
select * from finish();
rollback;
