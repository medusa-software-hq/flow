#!/usr/bin/env bash
#
# Seed — or reset — the sandbox fixture repository the staging stack and the nightly run against.
#
# Idempotent: run it any number of times to return the sandbox to its canonical state. It restores
# the baseline `main`, deletes the branches/PRs a prior loop left behind, and recreates the
# `flow:ready` issue chain (with a native "blocked by" edge). GitHub never reuses issue numbers, so
# the chain is *recreated* each time rather than reopened; older runs' issues stay closed.
#
# Requires: `gh` authenticated as an admin of the sandbox org, and `git`.
#
# The credential boundary (documented in README.md): the staging App lives ONLY here, never on a
# prod repo; the prod App is never installed here. This script only ever touches the sandbox org.

set -euo pipefail

ORG="medusa-software-test-hq"
REPO="flow-sandbox-fixture"
FULL="$ORG/$REPO"
DEFAULT_BRANCH="main"
READY_LABEL="flow:ready"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixture"

log() { echo "[seed] $*"; }

# --- 1. The repository ---------------------------------------------------------------------------

if gh repo view "$FULL" >/dev/null 2>&1; then
  log "repo $FULL exists"
else
  log "creating $FULL (private, mirroring prod's posture)"
  gh repo create "$FULL" \
    --private \
    --description "Flow sandbox fixture — seeded by sandbox/seed.sh; safe to reset at any time."
fi

# --- 2. The baseline ------------------------------------------------------------------------------
# Force-push the fixture tree as a single commit, so `main` is exactly the seeded baseline no matter
# what a prior loop merged into it.

WORKTREE="$(mktemp -d)"
trap 'rm -rf "$WORKTREE"' EXIT

cp -R "$FIXTURE_DIR/." "$WORKTREE/"

git -C "$WORKTREE" init -q -b "$DEFAULT_BRANCH"
git -C "$WORKTREE" add -A
# Pin identity and disable signing here rather than relying on the operator's global git config
# (a global commit.gpgsign=true breaks this throwaway commit).
git -C "$WORKTREE" \
  -c user.email="flow-sandbox@medusa.software" \
  -c user.name="Flow Sandbox" \
  -c commit.gpgsign=false \
  commit -q -m "Seed sandbox fixture"

# Auth git via gh's token in a header (never in the URL, so it isn't exposed in `ps`).
AUTH_HEADER="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$(gh auth token)" | base64 | tr -d '\n')"
git -C "$WORKTREE" \
  -c "http.https://github.com/.extraheader=$AUTH_HEADER" \
  push -f "https://github.com/$FULL.git" "$DEFAULT_BRANCH"
log "pushed the baseline to $DEFAULT_BRANCH"

# --- 3. Clean up a prior loop's leavings ----------------------------------------------------------

log "closing open pull requests"
gh pr list --repo "$FULL" --state open --json number --jq '.[].number' | while read -r pr; do
  gh pr close "$pr" --repo "$FULL" --delete-branch || true
done

log "deleting stray flow/* branches"
# `grep` exits non-zero when there are no matches; tolerate it (pipefail would otherwise abort).
gh api "repos/$FULL/branches" --paginate --jq '.[].name' | { grep '^flow/' || true; } | while read -r branch; do
  gh api -X DELETE "repos/$FULL/git/refs/heads/$branch" || true
done

log "closing leftover open issues"
gh issue list --repo "$FULL" --state open --json number --jq '.[].number' | while read -r issue; do
  gh issue close "$issue" --repo "$FULL" || true
done

# --- 4. The label ---------------------------------------------------------------------------------

gh label create "$READY_LABEL" --repo "$FULL" --color "0e8a16" \
  --description "Flow may pick this issue up" 2>/dev/null || true

# --- 5. The ready-issue chain ---------------------------------------------------------------------
# A is immediately pickable; B is native-blocked-by A, so Flow must close A before B becomes
# pickable — the dependency edge the reconciler reads via GraphQL `blockedBy`.

create_ready_issue() {
  local title="$1" body="$2"
  gh issue create --repo "$FULL" --title "$title" --body "$body" --label "$READY_LABEL" \
    | grep -oE '[0-9]+$'
}

log "creating the ready-issue chain"
ISSUE_A="$(create_ready_issue \
  "Change the greeting to Goodbye" \
  "The application greets with \`Hello\` (see \`Greeter.GREETING\`). Change it to \`Goodbye\`, and update \`GreetingCheck.EXPECTED_GREETING\` to match so \`verifyGreeting\` still passes.")"

ISSUE_B="$(create_ready_issue \
  "Add a farewell to the greeter" \
  "Add a \`Greeter.FAREWELL\` constant set to \`Bye\`. (Depends on the greeting change landing first.)")"

log "issues created: #$ISSUE_A (ready), #$ISSUE_B (to be blocked by #$ISSUE_A)"

# Add the native blocked-by edge: B blocked by A. The dependencies API keys on the blocker's
# database id, not its number.
# The dependencies API keys on the blocker's database id (not its issue number), typed as an
# integer — hence `-F` (typed) rather than `-f` (string).
ISSUE_A_ID="$(gh api "repos/$FULL/issues/$ISSUE_A" --jq '.id')"
if gh api -X POST "repos/$FULL/issues/$ISSUE_B/dependencies/blocked_by" \
  -F "issue_id=$ISSUE_A_ID" >/dev/null 2>&1; then
  log "added native blocked-by: #$ISSUE_B blocked by #$ISSUE_A"
else
  log "WARNING: could not add the blocked-by edge via the dependencies API; #$ISSUE_B is unblocked."
  log "         (Flow will still pick both, just without the dependency ordering.)"
fi

log "done. Sandbox reset to its canonical seeded state."
