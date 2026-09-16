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

## Guarded web/function deployments

Read `docs/deployment-policy.md` first. After the guardrails PR is reviewed and
merged, **Deploy web platform** automatically follows successful `main` CI.
It deploys the configured web artifact and invoice-ingest function from the
same exact SHA. **Roll back web platform** is dispatched on `main` and requires
owner approval in `production-rollback`.

Repository variables contain only the public client URL/publishable key.
Both production environments have a main-only branch allowlist, an encrypted
`BUDGETGUARD_DEPLOY_KEY`, a pinned `BUDGETGUARD_SSH_KNOWN_HOSTS` secret and a
`BUDGETGUARD_DEPLOY_HOST` variable. Do not use root/ubuntu's SSH key for CI.

The dedicated `budgetguard-deploy` identity has a root-owned SSH forced command
and narrow sudo permission. Its allowed commands are `verify`, `deploy <sha>`
and `rollback <sha|previous>`; uploads can only contain bounded static web files.
Migration SQL and function source come from GitHub's exact current tested `main`
SHA, not uploaded scripts. Controller/policy files are installed by an authorized
owner and never self-updated by a release.

Server paths:

- Root-owned controller/policy: `/opt/budgetguard-deploy`
- Protected lock, current release, manifests and deployment dumps:
  `/var/lib/budgetguard-deploy`
- Retained web releases: `/var/www/budget-guard/releases/<full-sha>`
- Retained function copies inside the existing Docker bind mount:
  `volumes/functions/.budgetguard-releases/<full-sha>`
- Private checksum ledger: `deployment_control.migrations` (not exposed via API)

Owner-only initial setup, using a reviewed checkout and a dedicated Ed25519
public key (never a private key file as the second argument):

```sh
sudo bash deploy/kaleva/install-controller.sh /absolute/audited/checkout /absolute/deploy-key.pub
sudo /opt/budgetguard-deploy/bootstrap-ledger.py /absolute/audited/checkout
```

Bootstrap is one-time: it requires the five audited historical SQL files and
baseline JSON, verifies existing schema evidence, makes and restore-tests a dump,
then records checksums **without executing** those historical files. It refuses
an existing ledger. Do not run this for future migrations.

Before changing controllers, review the diff, run deployment tests, retain the
current installed controller/policy for operator rollback and reinstall as the
owner. CI cannot install these files. Daily backups retain their 14-day schedule;
deployment recovery dumps have no automatic pruning yet. Monitor disk space and
obtain approval before deleting recovery material. Neither dump family covers
private Storage file bytes or off-host recovery.
