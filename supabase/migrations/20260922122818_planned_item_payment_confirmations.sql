create table public.planned_item_payment_confirmations (
  planned_item_id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  paid_on date not null default current_date,
  created_at timestamptz not null default now(),
  foreign key (user_id, planned_item_id)
    references public.planned_items(user_id, id)
    on delete cascade
);

alter table public.planned_item_payment_confirmations enable row level security;

revoke all on table public.planned_item_payment_confirmations from public, anon;
grant select, insert, delete on table public.planned_item_payment_confirmations to authenticated;

create policy "payment_confirmations_select_own"
on public.planned_item_payment_confirmations
for select
to authenticated
using ((select auth.uid()) = user_id);

create policy "payment_confirmations_insert_own_expense"
on public.planned_item_payment_confirmations
for insert
to authenticated
with check (
  (select auth.uid()) = user_id
  and exists (
    select 1
    from public.planned_items item
    where item.id = planned_item_id
      and item.user_id = (select auth.uid())
      and item.direction = 'expense'
  )
);

create policy "payment_confirmations_delete_own"
on public.planned_item_payment_confirmations
for delete
to authenticated
using ((select auth.uid()) = user_id);
