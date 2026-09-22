-- Synthetic fixtures only. The transaction rolls everything back.
begin;
select plan(12);
insert into auth.users(id,email) values
('11111111-1111-4111-8111-111111111111','owner-a@example.invalid'),
('22222222-2222-4222-8222-222222222222','owner-b@example.invalid');
insert into public.entities(id,user_id,name) values
('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','11111111-1111-4111-8111-111111111111','Synthetic business A'),
('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','11111111-1111-4111-8111-111111111111','Synthetic business B');
insert into public.accounts(id,user_id,entity_id,external_key,name,type,mask) values
('aaaaaaaa-0000-4000-8000-000000000001','11111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','synthetic-a','Synthetic A','cheque','0001');
insert into public.invoices(user_id,inbox_id,email_from,received_at,original_file_name,document_path,content_sha256)
select user_id,id,'supplier@example.invalid',now(),'synthetic.pdf',user_id::text || '/synthetic.pdf',repeat('a',64)
from public.invoice_inboxes where user_id in ('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222');
insert into storage.objects(bucket_id,name) values
('invoice-documents','11111111-1111-4111-8111-111111111111/synthetic.pdf'),
('invoice-documents','22222222-2222-4222-8222-222222222222/synthetic.pdf');

set local role authenticated;
select set_config('request.jwt.claims','{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}',true);
select is((select count(*) from public.entities where user_id='22222222-2222-4222-8222-222222222222'),0::bigint,'another user entities are invisible');
select is((select count(*) from public.accounts where user_id='22222222-2222-4222-8222-222222222222'),0::bigint,'another user accounts are invisible');
select is((select count(*) from public.invoice_inboxes),1::bigint,'only own forwarding inbox is visible');
select is((select count(*) from public.invoices),1::bigint,'only own invoice is visible');
select is((select count(*) from storage.objects where bucket_id='invoice-documents'),1::bigint,'only own PDF object is visible');
select is((select count(*) from public.accounts where id='aaaaaaaa-0000-4000-8000-000000000001'),1::bigint,'owner can read assigned account');
select throws_ok($$insert into public.entities(user_id,name) values ('22222222-2222-4222-8222-222222222222','Unauthorized entity')$$,'42501',null,'cannot create entities for another user');
select throws_ok($$insert into public.accounts(user_id,entity_id,external_key,name,type,mask) values ('11111111-1111-4111-8111-111111111111',(select id from public.entities where user_id='11111111-1111-4111-8111-111111111111' and is_default),'synthetic-cross','Cross user account','cheque','0002'); update public.accounts set user_id='22222222-2222-4222-8222-222222222222' where external_key='synthetic-cross'$$,'42501',null,'cannot transfer account ownership to another user');
select throws_ok($$insert into public.budget_periods(user_id,entity_id,starts_on) values ('11111111-1111-4111-8111-111111111111','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','2026-10-01'); insert into public.planned_items(user_id,entity_id,budget_period_id,account_id,direction,kind,name,planned_cents) select '11111111-1111-4111-8111-111111111111','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',id,'aaaaaaaa-0000-4000-8000-000000000001','expense','variable_expense','Cross entity',100 from public.budget_periods where entity_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' and starts_on='2026-10-01'$$,'P0001','The selected account belongs to a different entity.','cannot attach another entity account to a plan');

reset role;
set local role anon;
select set_config('request.jwt.claims','{"role":"anon"}',true);
select throws_ok('select * from public.accounts','42501',null,'anonymous users cannot query bank accounts');
select throws_ok('select * from public.invoices','42501',null,'anonymous users cannot query invoices');
select is((select count(*) from storage.objects where bucket_id='invoice-documents'),0::bigint,'anonymous users cannot read PDF objects');
reset role;
select * from finish();
rollback;
