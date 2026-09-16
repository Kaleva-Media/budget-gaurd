begin;
select plan(1);
select is((select relrowsecurity from pg_class where oid = 'public.planned_item_matches'::regclass), true, 'planned_item_matches has RLS enabled');
select * from finish();
rollback;
