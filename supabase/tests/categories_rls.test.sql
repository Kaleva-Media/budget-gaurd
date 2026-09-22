begin;
select plan(3);
select is((select relrowsecurity from pg_class where oid = 'public.categories'::regclass), true, 'categories has RLS enabled');
select has_column('public', 'categories', 'category_scope', 'categories distinguish personal and business expense types');
select has_index('public', 'categories', 'categories_user_scope_order_idx', 'category lookup is indexed by owner and scope');
select * from finish();
rollback;
