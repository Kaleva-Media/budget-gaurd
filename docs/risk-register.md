# BudgetGuard risk register

Version: **v0.1**

**Owner (register):** Amara (Security)  
**Reviewer:** Gugu (Business Lead)  
**Residual risk acceptance:** Edward Motloung  
**Date:** 2026-09-17 (Africa/Johannesburg)  
**Sources:** `AGENTS.md`, `docs/architecture.md`, `docs/handoff.md`, `deploy/kaleva/README.md`, `deploy/kaleva/backup-postgres.sh`  
**Baseline:** repo `main` @ handoff note `9a19439` — document state, not a fresh live audit  
**Standing control:** Amara may block unsafe production releases (invoice Worker/function, RLS, signing, Storage)

Severity: **Critical** / **High** / **Medium** / **Low**  
Status: **Open** / **Mitigating** / **Accept-pending** / **Accepted**

---

## R1 — SMS content exposure and over-collection

| Field | Detail |
| --- | --- |
| **Area** | SMS handling |
| **Risk** | Raw SMS, OTPs, login links, electricity tokens, or fraud messages leave the device or enter logs/backups; spoofed sender labels are treated as authentic bank events. |
| **Impact** | Credential theft, privacy breach (POPIA), corrupted ledger from spoofed SMS. |
| **Likelihood** | Medium (device has SMS permission; parsers are bank-specific but not perfect). |
| **Severity** | High |
| **Current controls** | Raw SMS discarded after parse; prepaid electricity receipts explicitly discarded; Absa/FNB format gates; `READ_SMS` only after user opt-in + disclosure; Android local DB backups disabled; no notification of transaction contents. |
| **Gaps** | Live device regression not recorded 2026-09-17; merchant-reference redaction policy undecided; CI does not cover installed Android UI. |
| **Owner** | Neo (Android) + Amara (policy) |
| **Mitigation** | Keep AGENTS.md bans; Vera regression for discard paths; decide merchant redaction with Priya; no production change that uploads raw bodies. |
| **Accept?** | Not recommended. Residual after mitigations: Low — Edward accept only if redaction decision is deferred with explicit date. |

## R2 — Invoice PDF and extracted bank-detail misuse

| Field | Detail |
| --- | --- |
| **Area** | Invoice PDFs |
| **Risk** | PDF text / supplier bank details treated as instructions or payment authority; cross-user PDF read; diagnostic Worker endpoints left public; oversized/non-PDF abuse. |
| **Impact** | Fraudulent payment initiation, data leak of financial documents, account takeover of inbox path. |
| **Likelihood** | Medium (live delivery still broken/unverified; Worker had temporary HTTP probe historically). |
| **Severity** | Critical (if payment inferred) / High (document leak) |
| **Current controls** | PDF-only, size cap, alias shape checks; P-256 signed upload; private per-user Storage; invoices stay `needs_review` until user approval; bank details marked unverified; no payment initiation in product. |
| **Gaps** | Live Email Routing → Worker → function → Storage chain unverified; last live Worker may still differ from checked-in source (diagnostic probe); CI omits email ingress and Deno functions. |
| **Owner** | Ada (ingress/function) + Amara (review gate) |
| **Mitigation** | Amara security review **before** any production deploy of `email-ingress` or `invoice-ingest`; remove public diagnostic handlers; verify signature pair presence (not values); end-to-end test with one user-forwarded PDF; Vera cross-user Storage/RLS check. |
| **Accept?** | **Do not accept** production invoice path until delivery verified and probe gone. Block unsafe releases. |

## R3 — RLS / entity isolation failure

| Field | Detail |
| --- | --- |
| **Area** | RLS / entity isolation |
| **Risk** | Weakened RLS, security-invoker views, or ownership RPCs allow cross-user or cross-entity reads/writes (invoices, plans, accounts, PDFs). |
| **Impact** | Direct breach of other users' or entities' financial data. |
| **Likelihood** | Low–Medium (schema has RLS intent; production RLS tests not in CI; privileged DB counts do not prove RLS). |
| **Severity** | Critical |
| **Current controls** | Public tables revoke anon; RLS by `auth.uid()`; approval RPC validates entity/period/account ownership; AGENTS.md forbids weakening isolation for tests. |
| **Gaps** | `supabase test db` / RLS suite not in CI; no recorded dual-user isolation test on production 2026-09-17. |
| **Owner** | Ada (schema) + Vera (isolation tests) + Amara (release gate) |
| **Mitigation** | Add RLS tests to CI (Alex track); Vera dual-session tests before invoice go-live; Amara blocks PRs that relax RLS/Storage policies. |
| **Accept?** | Not recommended. Any temporary waive needs Edward + Amara written accept with expiry. |

## R4 — Backup and disaster recovery gaps

| Field | Detail |
| --- | --- |
| **Area** | Backups / DR |
| **Risk** | Same-host `pg_dump` only; PDF Storage not in backup set; host loss loses DB dumps and documents together; restore untested. |
| **Impact** | Permanent loss of budgets, invoices, and PDF originals after host failure or ransomware. |
| **Likelihood** | Medium (single host `kaleva.cloud`; 14-day local retention). |
| **Severity** | High |
| **Current controls** | Daily custom-format dump to `/home/ubuntu/backups/budget-guard`, mode `600`, 14-day prune (`backup-postgres.sh` + systemd timer). |
| **Gaps** | No off-host encrypted copy; private `invoice-documents` Storage not covered; no documented restore drill; DB dump ≠ full DR. |
| **Owner** | Ada (ops) + Amara (DR policy) + Edward (accept residual) |
| **Mitigation** | (1) Encrypted off-host DB dump destination. (2) Document and implement Storage object backup/restore. (3) Quarterly restore drill. Until then, treat as **known DR gap** (see statement below). |
| **Status** | **Open** — founder deferred formal accept (2026-09-17). Treat as known gap; mitigation proposed; **not Accepted**. |
| **Accept?** | Deferred. Re-ask Edward when off-host DB + Storage backup work is scheduled or before irreplaceable PDF volume grows. |

## R5 — Key and secret mishandling

| Field | Detail |
| --- | --- |
| **Area** | Keys |
| **Risk** | Service-role key or invoice private JWK in clients/repo/logs; key rotation without paired deploy; Node 16 / wrong Cloudflare account deploy; re-run of `provision-supabase.sh` on live stack. |
| **Impact** | Full database compromise, forged invoice ingest, stack destruction. |
| **Likelihood** | Medium (multi-agent ops; historical Node/runtime confusion; signing already rotated once). |
| **Severity** | Critical |
| **Current controls** | Publishable keys only in web/Android; private JWK in encrypted Worker secret; public JWK in function; secrets not committed; AGENTS.md bans printing full `.env`. |
| **Gaps** | Operator discipline across agents; Wrangler OAuth historically incomplete; no automated secret-scan in CI noted. |
| **Owner** | Ada (runtime secrets) + Amara (policy) + Alex (CI secret scan) |
| **Mitigation** | Check secret **presence** only; never rotate signing pair without evidence + Edward auth; Amara reviews any key-touching change; add secret scan to CI; never re-run provision script on existing production. |
| **Accept?** | Not recommended for any client-side service-role exposure. |

## R6 — Agent / operator access to production

| Field | Detail |
| --- | --- |
| **Area** | Agent access to production |
| **Risk** | Agents with SSH/Cloudflare/GitHub act beyond scoped authorization; mutate data; dump secrets into chat; force-push; enable apex Email Routing MX; leave diagnostic endpoints. |
| **Impact** | Unreviewed production change, data leak into agent transcripts, irreversible ops mistakes. |
| **Likelihood** | High without process (many specialists now on the project). |
| **Severity** | High |
| **Current controls** | AGENTS.md: production deploy, DNS, key rotation, data mutation, rollback need **current-task** user authorization; historical handoff auth is not standing permission; Amara release block for unsafe invoice path. |
| **Gaps** | No single production change log; agent transcripts may retain sensitive snippets if operators paste them. |
| **Owner** | Gugu (process) + Amara (security gate) + Edward (authorization) |
| **Mitigation** | Require Edward (or named delegate) scoped yes per production action; Amara mandatory review for Worker/function/RLS/Storage; redact logs before sharing; prefer read-only diagnostics first (Ada + Amara). |
| **Accept?** | Process must not be waived. Edward accepts individual residual risks only after Amara/Gugu review. |

## R7 — Incomplete CI and unverified production posture (supporting)

| Field | Detail |
| --- | --- |
| **Area** | Reliability / assurance |
| **Risk** | CI covers domain/web/parser only — not email ingress, Deno functions, DB RLS, or Android UI; live invoice path may remain broken while code looks "done". |
| **Impact** | False confidence; regressions reach production. |
| **Severity** | Medium |
| **Owner** | Alex (CI) + Vera (acceptance) + Amara (gate on security-critical paths) |
| **Mitigation** | Expand CI per handoff; block invoice production promote until Vera + Amara sign-off. |
| **Accept?** | Accept temporary CI gap with Edward awareness; do **not** accept promoting invoice path without the listed checks. |

---

## Backup / DR gap statement (for Edward)

**Clear statement:** BudgetGuard today does **not** have a complete disaster-recovery posture.

1. **Database:** Automated backups are **same-host** `pg_dump` custom dumps under `/home/ubuntu/backups/budget-guard` on `kaleva.cloud`, with ~14-day retention. If that host is lost, encrypted, or wiped, those dumps are lost with it.
2. **Invoice PDFs:** Private Storage (`invoice-documents`) **off-host recovery is not established**. A database restore alone does **not** bring back PDF originals.
3. **Implication:** Do not treat the current backup timer as "we can recover from a host failure." It mainly protects against accidental DB mistakes on a still-healthy host.
4. **Recommendation:** Schedule encrypted off-host DB backups and Storage backup/restore within the 30-day plan; run one restore drill. **Edward deferred formal accept on 2026-09-17** — R4 stays **Open** (known gap / mitigate proposed), not Accepted. Revisit before irreplaceable PDF volume grows.

---

## Release gate (Amara)

Before production deploy of **email-ingress Worker** or **invoice-ingest function**:

1. Diff reviewed by Amara (signatures, auth, size limits, no public probes, no RLS/Storage weakening).
2. Ada confirms secret **bindings** present (values not shared).
3. Vera plan for cross-user isolation check post-deploy.
4. Edward scoped authorization for that deploy.

**Unsafe by default if missing any of the above → Amara blocks.**

### Current production authorization note (2026-09-17)

Edward granted Ada **scoped read-only** production diagnosis for the invoice path (Worker settings/logs, secret **presence** only, invoice counts). That grant explicitly does **not** include deploy, DNS changes, or key rotation. Amara's release gate above remains in force for any later deploy request.

---

## Acceptance checklist

| ID | Mitigation or accept | Who |
| --- | --- | --- |
| R1 | Mitigate (redaction decision + Vera tests) | Neo / Priya / Vera |
| R2 | Mitigate; **block** until verified | Ada / Amara / Vera |
| R3 | Mitigate (RLS CI + dual-user tests) | Ada / Vera / Alex |
| R4 | **Open** (deferred accept) — known gap; schedule off-host + Storage DR | Edward / Ada / Amara |
| R5 | Mitigate (no waive) | Ada / Amara / Alex |
| R6 | Mitigate (scoped auth process) | Gugu / Edward / Amara |
| R7 | Accept CI gap short-term; not invoice promote | Alex / Edward |

---

*v0.1 — Gugu endorsed R1–R3, R5–R7 (2026-09-17). R4 **Open** (Edward deferred formal accept): known gap / mitigate proposed; **not Accepted** — do not imply founder acceptance of residual DR risk. Ada: scoped read-only invoice diagnosis only (no deploy in that grant). Amara release gate unchanged.*
