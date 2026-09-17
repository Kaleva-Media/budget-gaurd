# Branch Protection Relaxation Guide

## Current Status (Before)

All PRs on `main` branch are showing:
- `reviewDecision: REVIEW_REQUIRED` 
- HTTP 405 errors: "approval from someone other than the last pusher"

Branch protection is active but cannot be read/modified via the agent's API token (HTTP 403).

## Required Changes

Navigate to: https://github.com/edward-kalevamedia/budget-gaurd/settings/branches

### Option 1: Classic Branch Protection Rules

If using **Branch protection rules** for `main`:

1. Click "Edit" on the `main` branch rule
2. **UNCHECK** or set to **0**:
   - ☐ "Require a pull request before merging"
     - OR keep checked but set "Required number of approvals before merging" to **0**
   - ☐ "Dismiss stale pull request approvals when new commits are pushed"
   - ☐ "Require approval of the most recent reviewable push"
   - ☐ "Require review from Code Owners"
3. **KEEP** (if present):
   - ☑ "Require status checks to pass before merging"
     - Status checks: `all-tests` (and any other CI checks)
4. Click "Save changes"

### Option 2: Repository Rulesets

If using **Rulesets** instead (Settings > Rules > Rulesets):

1. Find the ruleset applying to `main`
2. Edit the ruleset
3. Under "Require pull request before merging":
   - Set "Required approvals" to **0**
   - ☐ Uncheck "Dismiss stale pull request approvals when new commits are pushed"
   - ☐ Uncheck "Require approval of the most recent reviewable push"
   - ☐ Uncheck "Require review from code owners"
4. Keep "Require status checks to pass" with `all-tests` if desired
5. Save the ruleset

## Alternative: Use Personal Access Token (CLI)

If you have a GitHub PAT with `repo` and `admin:repo_hook` scopes:

```bash
# Set your PAT
export GH_TOKEN="your_github_personal_access_token"

# Remove branch protection entirely (most direct)
gh api -X DELETE repos/edward-kalevamedia/budget-gaurd/branches/main/protection

# OR update protection to disable review requirements but keep status checks
gh api -X PUT repos/edward-kalevamedia/budget-gaurd/branches/main/protection \
  --input - <<EOF
{
  "required_status_checks": {
    "strict": false,
    "contexts": ["all-tests"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF
```

## Verification

After making changes, verify with:

```bash
# Check if PR #1 (with passing tests) can now merge
gh pr view 1 --json mergeable,reviewDecision,statusCheckRollup

# Expected result:
# - reviewDecision should be null or "APPROVED" (not "REVIEW_REQUIRED")
# - mergeable should be "MERGEABLE"
```

Or test merging PR #1 directly since all its tests passed.

## After (Expected State)

- edward-kalevamedia (Ada/agent) can merge PRs without separate approval
- Status checks like `all-tests` still required (CI must pass)
- Same GitHub user can author, approve, and merge their own PRs

---
*This change was authorized by Edward Motloung to enable agent PR workflows.*
