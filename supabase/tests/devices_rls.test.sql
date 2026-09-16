begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.devices'::regclass), true, 'devices has RLS enabled');
select * from finish();
rollback;
