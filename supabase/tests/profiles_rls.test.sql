begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.profiles'::regclass), true, 'profiles has RLS enabled');
select * from finish();
rollback;
