begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.transactions'::regclass), true, 'transactions has RLS enabled');
select * from finish();
rollback;
