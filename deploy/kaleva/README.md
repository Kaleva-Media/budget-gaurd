# Kaleva production deployment

BudgetGuard is deployed at `https://budget.cloudcomms.co.za` on the
`kaleva.cloud` host. Nginx serves the static React build and proxies the
Supabase API paths to an isolated, loopback-only Docker Compose stack.

The Supabase deployment is pinned to the upstream `self-hosted/v0.8.1`
release. Its `.env` contains generated secrets and must never be copied into
this repository. Supabase Studio and database-pooler ports are not public.

Authentication starts with email/password sign-up and email auto-confirmation.
SMTP must be configured before password-recovery email can deliver successfully.
Each new user is bootstrapped with the eight accounts described for this product
and fourteen quick-sort category buckets. The known Absa SMS keys `CHEQ1607`
and `SAVE2959` are ready immediately; the two credit-card masks still need to be
captured from a real notification and updated before those cards can sync.

Operational locations on Kaleva:

- Static web releases/current symlink: `/var/www/budget-guard`
- Supabase: `/home/ubuntu/Databases/supabase-fix/projects/budget-guard`
- Nginx vhost: `/etc/nginx/sites-available/budget.cloudcomms.co.za`
- API gateway: `127.0.0.1:12421`
- Pooler session/transaction ports: `127.0.0.1:12422` / `127.0.0.1:12423`
- Local database backups: `/home/ubuntu/backups/budget-guard` (daily, 14-day retention)

Invoice ingestion adds the `invoice-ingest` Edge Function and the private
`invoice-documents` Storage bucket. Inbound mail is handled by the
`budgetguard-invoice-ingress` Cloudflare Worker for the dedicated
`inbox.budget.cloudcomms.co.za` Email Routing subdomain. The Worker owns a P-256
private JWK; the Edge Function receives only the public JWK. Both deployments
must use the same key pair. Do not place either key in this repository, and do
not enable Email Routing MX records on the apex `cloudcomms.co.za` domain.

The local backup protects against accidental database loss but is not an
off-host disaster-recovery copy. Add an encrypted remote backup destination
before treating this as the only copy of important financial history.
