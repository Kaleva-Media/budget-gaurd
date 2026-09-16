begin;
select plan(12);

select is((select relrowsecurity from pg_class where oid = 'public.invoice_inboxes'::regclass), true, 'invoice inboxes have RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.invoices'::regclass), true, 'invoices have RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.suppliers'::regclass), true, 'suppliers have RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.supplier_bank_accounts'::regclass), true, 'supplier bank accounts have RLS enabled');
select is((select relrowsecurity from pg_class where oid = 'public.invoice_payment_plans'::regclass), true, 'invoice payment plans have RLS enabled');
select has_column('public', 'invoice_inboxes', 'address', 'invoice inbox exposes its generated forwarding address');
select has_column('public', 'invoices', 'outstanding_cents', 'invoice keeps the extracted outstanding amount');
select has_column('public', 'invoices', 'extracted_fields', 'invoice keeps auditable extraction evidence');
select has_column('public', 'supplier_bank_accounts', 'is_verified', 'supplier bank details are unverified by default');
select has_column('public', 'invoice_payment_plans', 'planned_cents', 'invoice payment plans support partial monthly payments');
select has_function('public', 'approve_invoice', array['uuid', 'uuid', 'uuid', 'uuid', 'uuid', 'bigint'], 'invoice approval function exists');
select ok(
  exists (select 1 from storage.buckets where id = 'invoice-documents' and not public),
  'invoice documents use a private storage bucket'
);

select * from finish();
rollback;
