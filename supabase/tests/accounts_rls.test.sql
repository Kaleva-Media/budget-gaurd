begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.accounts'::regclass), true, 'accounts has RLS enabled');
select * from finish();
rollback;
