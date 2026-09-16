## Change and acceptance criteria

Describe the user-visible result and how to verify it.

## Required evidence

- [ ] Latest PR commit SHA and all CI suites pass (no skipped/cancelled suites).
- [ ] Regression tests cover the change; security-sensitive changes include negative/ownership cases.
- [ ] No credentials, private inbox aliases, real PDFs, raw SMS, or financial data are committed.
- [ ] Existing migrations are unchanged; new migrations are additive and compatible with the previous release.
- [ ] Rollback target/steps and any data effects are described below.
- [ ] No public database/pooler/Studio bind or proxy is introduced.
- [ ] Agent guide/handoff/runbooks are updated if behaviour or operations changed.

## Database and deployment impact

List migration filenames, compatibility evidence, backup requirements, and smoke tests (or “none”).

## Rollback

Describe the retained release to restore. Do not promise automatic database restoration: it can discard newer writes and needs an authorised recovery plan.
