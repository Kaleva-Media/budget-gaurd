create table public.invoice_inboxes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  local_part text not null check (local_part ~ '^invoice-[a-f0-9]{24}$'),
  domain text not null default 'inbox.budget.cloudcomms.co.za'
    check (domain = 'inbox.budget.cloudcomms.co.za'),
  address text generated always as (local_part || '@' || domain) stored,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id),
  unique (user_id, id),
  unique (local_part, domain),
  unique (address)
);

create table public.suppliers (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  normalized_name text not null check (char_length(normalized_name) between 2 and 120),
  legal_name text not null check (char_length(trim(legal_name)) between 1 and 160),
  trading_name text,
  registration_number text,
  vat_number text,
  contact_email text,
  email_domain text,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, normalized_name),
  unique (user_id, id)
);

create table public.supplier_bank_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  supplier_id uuid not null,
  bank_name text,
  account_holder text,
  account_number text not null check (char_length(trim(account_number)) between 4 and 40),
  account_number_mask text not null,
  branch_code text,
  account_type text,
  account_fingerprint text not null check (char_length(account_fingerprint) = 64),
  is_verified boolean not null default false,
  verified_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (user_id, supplier_id) references public.suppliers (user_id, id) on delete cascade,
  unique (user_id, supplier_id, account_fingerprint),
  unique (user_id, id),
  check ((is_verified and verified_at is not null) or (not is_verified and verified_at is null))
);

create table public.invoices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  inbox_id uuid not null,
  entity_id uuid,
  supplier_id uuid,
  email_message_id text,
  email_from text not null,
  email_subject text,
  received_at timestamptz not null,
  original_file_name text not null,
  document_path text not null,
  content_sha256 text not null check (char_length(content_sha256) = 64),
  status text not null default 'needs_review'
    check (status in ('needs_review', 'approved', 'partially_paid', 'paid', 'rejected', 'failed')),
  extraction_status text not null default 'complete'
    check (extraction_status in ('complete', 'needs_ocr', 'failed')),
  extraction_confidence numeric(4, 3) not null default 0
    check (extraction_confidence between 0 and 1),
  supplier_name text,
  supplier_registration_number text,
  supplier_vat_number text,
  supplier_contact_email text,
  invoice_number text,
  issue_date date,
  due_date date,
  currency text not null default 'ZAR' check (currency ~ '^[A-Z]{3}$'),
  total_cents bigint check (total_cents is null or total_cents >= 0),
  outstanding_cents bigint check (outstanding_cents is null or outstanding_cents >= 0),
  payment_reference text,
  bank_name text,
  bank_account_holder text,
  bank_account_number text,
  bank_branch_code text,
  bank_account_type text,
  extracted_fields jsonb not null default '{}'::jsonb,
  extraction_warnings text[] not null default '{}',
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (user_id, inbox_id) references public.invoice_inboxes (user_id, id) on delete restrict,
  foreign key (user_id, entity_id) references public.entities (user_id, id) on delete restrict,
  foreign key (user_id, supplier_id) references public.suppliers (user_id, id) on delete restrict,
  unique (user_id, content_sha256),
  unique (user_id, id)
);

create table public.invoice_payment_plans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  entity_id uuid not null,
  invoice_id uuid not null,
  budget_period_id uuid not null,
  planned_item_id uuid not null,
  planned_cents bigint not null check (planned_cents > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  foreign key (user_id, entity_id) references public.entities (user_id, id) on delete cascade,
  foreign key (user_id, invoice_id) references public.invoices (user_id, id) on delete cascade,
  foreign key (user_id, entity_id, budget_period_id)
    references public.budget_periods (user_id, entity_id, id) on delete cascade,
  foreign key (user_id, planned_item_id) references public.planned_items (user_id, id) on delete cascade,
  unique (invoice_id, budget_period_id),
  unique (user_id, id)
);

create index invoice_inboxes_user_active_idx on public.invoice_inboxes (user_id, is_active);
create index suppliers_user_name_idx on public.suppliers (user_id, legal_name);
create index supplier_bank_accounts_supplier_idx on public.supplier_bank_accounts (user_id, supplier_id);
create index invoices_user_status_received_idx on public.invoices (user_id, status, received_at desc);
create index invoices_user_entity_idx on public.invoices (user_id, entity_id, received_at desc);
create index invoice_payment_plans_invoice_idx on public.invoice_payment_plans (user_id, invoice_id);

create trigger invoice_inboxes_set_updated_at before update on public.invoice_inboxes
for each row execute function public.set_updated_at();
create trigger suppliers_set_updated_at before update on public.suppliers
for each row execute function public.set_updated_at();
create trigger supplier_bank_accounts_set_updated_at before update on public.supplier_bank_accounts
for each row execute function public.set_updated_at();
create trigger invoices_set_updated_at before update on public.invoices
for each row execute function public.set_updated_at();
create trigger invoice_payment_plans_set_updated_at before update on public.invoice_payment_plans
for each row execute function public.set_updated_at();

alter table public.invoice_inboxes enable row level security;
alter table public.suppliers enable row level security;
alter table public.supplier_bank_accounts enable row level security;
alter table public.invoices enable row level security;
alter table public.invoice_payment_plans enable row level security;

revoke all on table public.invoice_inboxes, public.suppliers, public.supplier_bank_accounts,
  public.invoices, public.invoice_payment_plans from anon;
grant select on table public.invoice_inboxes to authenticated;
grant select, insert, update, delete on table public.suppliers, public.supplier_bank_accounts to authenticated;
grant select, update, delete on table public.invoices to authenticated;
grant select, insert, update, delete on table public.invoice_payment_plans to authenticated;

create policy "invoice_inboxes_select_own" on public.invoice_inboxes for select to authenticated
using ((select auth.uid()) = user_id);

create policy "suppliers_select_own" on public.suppliers for select to authenticated
using ((select auth.uid()) = user_id);
create policy "suppliers_insert_own" on public.suppliers for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "suppliers_update_own" on public.suppliers for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "suppliers_delete_own" on public.suppliers for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "supplier_bank_accounts_select_own" on public.supplier_bank_accounts for select to authenticated
using ((select auth.uid()) = user_id);
create policy "supplier_bank_accounts_insert_own" on public.supplier_bank_accounts for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "supplier_bank_accounts_update_own" on public.supplier_bank_accounts for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "supplier_bank_accounts_delete_own" on public.supplier_bank_accounts for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "invoices_select_own" on public.invoices for select to authenticated
using ((select auth.uid()) = user_id);
create policy "invoices_update_own" on public.invoices for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "invoices_delete_own" on public.invoices for delete to authenticated
using ((select auth.uid()) = user_id);

create policy "invoice_payment_plans_select_own" on public.invoice_payment_plans for select to authenticated
using ((select auth.uid()) = user_id);
create policy "invoice_payment_plans_insert_own" on public.invoice_payment_plans for insert to authenticated
with check ((select auth.uid()) = user_id);
create policy "invoice_payment_plans_update_own" on public.invoice_payment_plans for update to authenticated
using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "invoice_payment_plans_delete_own" on public.invoice_payment_plans for delete to authenticated
using ((select auth.uid()) = user_id);

create function public.ensure_invoice_inbox()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  insert into public.invoice_inboxes (user_id, local_part)
  values (new.id, 'invoice-' || encode(extensions.gen_random_bytes(12), 'hex'))
  on conflict (user_id) do nothing;
  return new;
end;
$$;

revoke all on function public.ensure_invoice_inbox() from public, anon, authenticated;

create trigger profiles_ensure_invoice_inbox
after insert on public.profiles
for each row execute function public.ensure_invoice_inbox();

insert into public.invoice_inboxes (user_id, local_part)
select p.id, 'invoice-' || encode(extensions.gen_random_bytes(12), 'hex')
from public.profiles p
on conflict (user_id) do nothing;

create or replace function public.approve_invoice(
  p_invoice_id uuid,
  p_entity_id uuid,
  p_budget_period_id uuid,
  p_category_id uuid,
  p_account_id uuid,
  p_payment_cents bigint
)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_invoice public.invoices%rowtype;
  v_normalized_name text;
  v_supplier_id uuid;
  v_planned_item_id uuid;
  v_account_fingerprint text;
begin
  if v_user_id is null then
    raise exception 'Authentication is required.';
  end if;

  if p_payment_cents <= 0 then
    raise exception 'Payment amount must be greater than zero.';
  end if;

  select * into v_invoice
  from public.invoices
  where id = p_invoice_id and user_id = v_user_id
  for update;

  if not found then
    raise exception 'Invoice not found.';
  end if;
  if v_invoice.status <> 'needs_review' then
    raise exception 'Only invoices awaiting review can be approved.';
  end if;
  if coalesce(v_invoice.outstanding_cents, v_invoice.total_cents) is not null
     and p_payment_cents > coalesce(v_invoice.outstanding_cents, v_invoice.total_cents) then
    raise exception 'Payment amount cannot exceed the outstanding invoice amount.';
  end if;
  if nullif(trim(v_invoice.supplier_name), '') is null then
    raise exception 'Supplier name must be confirmed before approval.';
  end if;

  perform 1 from public.entities
  where id = p_entity_id and user_id = v_user_id and is_active;
  if not found then raise exception 'Entity not found.'; end if;

  perform 1 from public.budget_periods
  where id = p_budget_period_id and user_id = v_user_id and entity_id = p_entity_id;
  if not found then raise exception 'Budget period does not belong to the selected entity.'; end if;

  if p_category_id is not null then
    perform 1 from public.categories where id = p_category_id and user_id = v_user_id;
    if not found then raise exception 'Category not found.'; end if;
  end if;

  if p_account_id is not null then
    perform 1 from public.accounts
    where id = p_account_id and user_id = v_user_id and entity_id = p_entity_id and is_active;
    if not found then raise exception 'Payment account does not belong to the selected entity.'; end if;
  end if;

  v_normalized_name := regexp_replace(lower(trim(v_invoice.supplier_name)), '[^a-z0-9]+', '', 'g');
  if char_length(v_normalized_name) < 2 then
    raise exception 'Supplier name is too short.';
  end if;

  insert into public.suppliers (
    user_id, normalized_name, legal_name, registration_number, vat_number, contact_email, email_domain
  ) values (
    v_user_id,
    v_normalized_name,
    trim(v_invoice.supplier_name),
    nullif(trim(v_invoice.supplier_registration_number), ''),
    nullif(trim(v_invoice.supplier_vat_number), ''),
    nullif(trim(v_invoice.supplier_contact_email), ''),
    nullif(split_part(lower(trim(v_invoice.supplier_contact_email)), '@', 2), '')
  )
  on conflict (user_id, normalized_name) do update set
    legal_name = excluded.legal_name,
    registration_number = coalesce(excluded.registration_number, public.suppliers.registration_number),
    vat_number = coalesce(excluded.vat_number, public.suppliers.vat_number),
    contact_email = coalesce(excluded.contact_email, public.suppliers.contact_email),
    email_domain = coalesce(excluded.email_domain, public.suppliers.email_domain)
  returning id into v_supplier_id;

  if nullif(regexp_replace(coalesce(v_invoice.bank_account_number, ''), '\s+', '', 'g'), '') is not null then
    v_account_fingerprint := encode(
      extensions.digest(
        lower(coalesce(v_invoice.bank_name, '') || '|' || regexp_replace(v_invoice.bank_account_number, '\s+', '', 'g') || '|' || coalesce(v_invoice.bank_branch_code, '')),
        'sha256'
      ),
      'hex'
    );
    insert into public.supplier_bank_accounts (
      user_id, supplier_id, bank_name, account_holder, account_number, account_number_mask,
      branch_code, account_type, account_fingerprint
    ) values (
      v_user_id,
      v_supplier_id,
      nullif(trim(v_invoice.bank_name), ''),
      nullif(trim(v_invoice.bank_account_holder), ''),
      regexp_replace(v_invoice.bank_account_number, '\s+', '', 'g'),
      '**** ' || right(regexp_replace(v_invoice.bank_account_number, '\s+', '', 'g'), 4),
      nullif(trim(v_invoice.bank_branch_code), ''),
      nullif(trim(v_invoice.bank_account_type), ''),
      v_account_fingerprint
    )
    on conflict (user_id, supplier_id, account_fingerprint) do nothing;
  end if;

  insert into public.planned_items (
    user_id, entity_id, budget_period_id, direction, kind, name, planned_cents,
    account_id, category_id, due_day, recurrence, sort_order
  ) values (
    v_user_id,
    p_entity_id,
    p_budget_period_id,
    'expense',
    'fixed_expense',
    trim(v_invoice.supplier_name) || coalesce(' · ' || nullif(trim(v_invoice.invoice_number), ''), ''),
    p_payment_cents,
    p_account_id,
    p_category_id,
    case when v_invoice.due_date is null then null else extract(day from v_invoice.due_date)::smallint end,
    'once',
    100
  ) returning id into v_planned_item_id;

  insert into public.invoice_payment_plans (
    user_id, entity_id, invoice_id, budget_period_id, planned_item_id, planned_cents
  ) values (
    v_user_id, p_entity_id, v_invoice.id, p_budget_period_id, v_planned_item_id, p_payment_cents
  );

  update public.invoices
  set entity_id = p_entity_id,
      supplier_id = v_supplier_id,
      status = 'approved',
      reviewed_at = now()
  where id = v_invoice.id and user_id = v_user_id;

  return v_planned_item_id;
end;
$$;

revoke all on function public.approve_invoice(uuid, uuid, uuid, uuid, uuid, bigint) from public, anon;
grant execute on function public.approve_invoice(uuid, uuid, uuid, uuid, uuid, bigint) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('invoice-documents', 'invoice-documents', false, 15728640, array['application/pdf'])
on conflict (id) do update set
  public = false,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create policy "invoice_documents_select_own" on storage.objects for select to authenticated
using (
  bucket_id = 'invoice-documents'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy "invoice_documents_delete_own" on storage.objects for delete to authenticated
using (
  bucket_id = 'invoice-documents'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);
