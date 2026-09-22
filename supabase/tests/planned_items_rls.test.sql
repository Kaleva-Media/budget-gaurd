begin;
select plan(2);
select is((select relrowsecurity from pg_class where oid = 'public.planned_items'::regclass), true, 'planned_items has RLS enabled');
select has_function('public', 'move_planned_item', array['uuid', 'uuid', 'uuid'], 'planned items can be moved atomically');
select * from finish();
rollback;
