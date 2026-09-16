begin;
select plan(7);

select is((select relrowsecurity from pg_class where oid = 'public.entities'::regclass), true, 'entities has RLS enabled');
select has_column('public', 'accounts', 'entity_id', 'accounts are entity scoped');
select has_column('public', 'budget_periods', 'entity_id', 'periods are entity scoped');
select has_column('public', 'budgets', 'entity_id', 'budgets are entity scoped');
select has_column('public', 'transactions', 'entity_id', 'transactions are entity scoped');
select has_column('public', 'planned_items', 'entity_id', 'planned items are entity scoped');
select ok(
  exists (
    select 1
    from pg_indexes
    where schemaname = 'public'
      and indexname = 'entities_one_default_per_user_idx'
  ),
  'only one default entity is allowed per user'
);

select * from finish();
rollback;
