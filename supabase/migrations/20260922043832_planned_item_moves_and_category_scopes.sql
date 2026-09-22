alter table public.categories
  add column category_scope text default 'personal'
  check (coalesce(category_scope in ('personal', 'business'), false));

create index categories_user_scope_order_idx
on public.categories (user_id, category_scope, sort_order, name);

insert into public.categories (user_id, name, colour, icon, system_key, sort_order, category_scope)
select
  p.id,
  category.name,
  category.colour,
  category.icon,
  category.system_key,
  category.sort_order,
  'business'
from public.profiles p
cross join (values
  ('Inventory & cost of sales', '#D9E8C4', 'package', 'business-cost-of-sales', 1),
  ('Payroll', '#C9DDF2', 'users', 'business-payroll', 2),
  ('Contractors', '#F4D7A1', 'briefcase-business', 'business-contractors', 3),
  ('Office & rent', '#D8D0EF', 'building-2', 'business-office-rent', 4),
  ('Software & subscriptions', '#BFE3D0', 'monitor-cog', 'business-software', 5),
  ('Marketing & advertising', '#F3C3C3', 'megaphone', 'business-marketing', 6),
  ('Professional services', '#C9E3E8', 'scale', 'business-professional-services', 7),
  ('Travel & transport', '#E2D4C2', 'plane', 'business-travel-transport', 8),
  ('Tax & compliance', '#D4DEB8', 'landmark', 'business-tax-compliance', 9),
  ('Banking & fees', '#D7C9EA', 'credit-card', 'business-banking-fees', 10),
  ('Insurance', '#F0C9DC', 'shield', 'business-insurance', 11),
  ('Utilities & communications', '#E8D6BA', 'radio-tower', 'business-utilities', 12),
  ('Equipment', '#BEDDE5', 'wrench', 'business-equipment', 13),
  ('Other business', '#E8E7E0', 'circle', 'business-other', 14)
) as category(name, colour, icon, system_key, sort_order)
on conflict (user_id, system_key) do nothing;

create function public.bootstrap_business_categories()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  insert into public.categories (user_id, name, colour, icon, system_key, sort_order, category_scope)
  values
    (new.id, 'Inventory & cost of sales', '#D9E8C4', 'package', 'business-cost-of-sales', 1, 'business'),
    (new.id, 'Payroll', '#C9DDF2', 'users', 'business-payroll', 2, 'business'),
    (new.id, 'Contractors', '#F4D7A1', 'briefcase-business', 'business-contractors', 3, 'business'),
    (new.id, 'Office & rent', '#D8D0EF', 'building-2', 'business-office-rent', 4, 'business'),
    (new.id, 'Software & subscriptions', '#BFE3D0', 'monitor-cog', 'business-software', 5, 'business'),
    (new.id, 'Marketing & advertising', '#F3C3C3', 'megaphone', 'business-marketing', 6, 'business'),
    (new.id, 'Professional services', '#C9E3E8', 'scale', 'business-professional-services', 7, 'business'),
    (new.id, 'Travel & transport', '#E2D4C2', 'plane', 'business-travel-transport', 8, 'business'),
    (new.id, 'Tax & compliance', '#D4DEB8', 'landmark', 'business-tax-compliance', 9, 'business'),
    (new.id, 'Banking & fees', '#D7C9EA', 'credit-card', 'business-banking-fees', 10, 'business'),
    (new.id, 'Insurance', '#F0C9DC', 'shield', 'business-insurance', 11, 'business'),
    (new.id, 'Utilities & communications', '#E8D6BA', 'radio-tower', 'business-utilities', 12, 'business'),
    (new.id, 'Equipment', '#BEDDE5', 'wrench', 'business-equipment', 13, 'business'),
    (new.id, 'Other business', '#E8E7E0', 'circle', 'business-other', 14, 'business');
  return new;
end;
$$;

revoke all on function public.bootstrap_business_categories() from public, anon, authenticated;

create trigger bootstrap_business_categories_profile
after insert on public.profiles
for each row execute function public.bootstrap_business_categories();

create function public.move_planned_item(
  p_planned_item_id uuid,
  p_target_entity_id uuid,
  p_target_budget_period_id uuid
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
  owner_id uuid := (select auth.uid());
  source_item public.planned_items%rowtype;
  target_scope text;
  keep_category boolean;
begin
  if owner_id is null then
    raise exception 'Authentication is required.';
  end if;

  select p.* into source_item
  from public.planned_items p
  where p.id = p_planned_item_id
    and p.user_id = owner_id
  for update;

  if not found then
    raise exception 'Planned item not found.';
  end if;

  select case when e.kind = 'personal' then 'personal' else 'business' end
  into target_scope
  from public.entities e
  join public.budget_periods period
    on period.user_id = e.user_id
   and period.entity_id = e.id
   and period.id = p_target_budget_period_id
  where e.user_id = owner_id
    and e.id = p_target_entity_id
    and e.is_active;

  if not found then
    raise exception 'Target entity or budget period not found.';
  end if;

  if exists (
    select 1
    from public.planned_item_matches m
    where m.user_id = owner_id
      and m.planned_item_id = p_planned_item_id
  ) then
    raise exception 'Entries with matched payments cannot be moved.';
  end if;

  keep_category := source_item.category_id is null or exists (
    select 1
    from public.categories c
    where c.user_id = owner_id
      and c.id = source_item.category_id
      and c.category_scope = target_scope
  );

  update public.planned_items
  set entity_id = p_target_entity_id,
      budget_period_id = p_target_budget_period_id,
      account_id = case
        when source_item.entity_id = p_target_entity_id then source_item.account_id
        else null
      end,
      category_id = case when keep_category then source_item.category_id else null end
  where id = p_planned_item_id
    and user_id = owner_id;
end;
$$;

revoke all on function public.move_planned_item(uuid, uuid, uuid) from public, anon;
grant execute on function public.move_planned_item(uuid, uuid, uuid) to authenticated;
