#!/usr/bin/env bash
#
# Nightly real-stack loop — the only layer that catches "GitHub changed under us" and "model
# behaviour drifted". It drives ONE real M2-style cycle against the deployed staging stack and the
# sandbox GitHub org, with production model wiring and real money:
#
#   reset sandbox fixtures  →  start a real worker in the runner  →  (staging reconciles every 3 min)
#   →  session created for issue A  →  worker completes it, opens a PR
#   →  control plane merges the PR + closes issue A  →  the blocked dependent (issue B) is picked
#
# Everything after "start a real worker" is autonomous on staging (Cloud Scheduler reconcile,
# */3 * * * *); this script only runs the worker and asserts the outcomes, via BOTH the staging API
# (gRPC-as-JSON, worker-SA ID token — like smoke/smoke.sh) and the GitHub API (gh, sandbox App token).
#
# Failure is meant to be diagnosable: every wait names the stage it is in, a stalled stage prints why,
# and the worker's full log is captured for the failure-report issue the workflow opens.
#
# Inputs (env):
#   API_URL            deployed staging API, e.g. https://api.flow-baseline-staging.medusa.software
#   WORKER_SA_EMAIL    the worker SA to impersonate for the assertion ID token
#   SANDBOX_TOKEN      a GitHub token (installation token from the medusa-flow-nightly App) with
#                      Contents/Issues/PRs write on the fixture repo — used for reset, the worker's
#                      git/PR operations, and the GitHub-side assertions
#   OPENROUTER_API_KEY the real model key (production model wiring)
#   CLI_JAR            path to the shipped flow-cli.jar (from :cli:shadowJar)
#   WORKER_ADC_FILE    ADC json that impersonates WORKER_SA_EMAIL (built by the workflow), so the
#                      worker's own Google ID token is a worker-class token staging accepts
#   WORKER_LOG         where to tee the worker's stdout/stderr (default: ./nightly-worker.log)
#
# Requires: gcloud (authenticated, able to impersonate WORKER_SA_EMAIL), gh, java, git, curl, python3.

set -uo pipefail

API_URL="${API_URL:?API_URL is required}"
WORKER_SA_EMAIL="${WORKER_SA_EMAIL:?WORKER_SA_EMAIL is required}"
SANDBOX_TOKEN="${SANDBOX_TOKEN:?SANDBOX_TOKEN is required}"
OPENROUTER_API_KEY="${OPENROUTER_API_KEY:?OPENROUTER_API_KEY is required}"
CLI_JAR="${CLI_JAR:?CLI_JAR is required}"
WORKER_ADC_FILE="${WORKER_ADC_FILE:?WORKER_ADC_FILE is required}"
WORKER_LOG="${WORKER_LOG:-nightly-worker.log}"

ORG="medusa-software-test-hq"
REPO="flow-sandbox-fixture"
FULL="$ORG/$REPO"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Timeouts (seconds). Reconcile runs every 3 min on staging, so each server-driven step is given a
# comfortable multiple of that; the AI leg gets the most.
SESSION_CREATE_TIMEOUT=360    # reconcile picks issue A and creates its session
SESSION_COMPLETE_TIMEOUT=900  # worker does the real AI task and opens the PR
PR_MERGE_TIMEOUT=720          # merge-gate grace + a reconcile pass merges the PR
ISSUE_CLOSE_TIMEOUT=180       # reconciler closes the issue on merge
DEPENDENT_TIMEOUT=420         # A closing unblocks B; next reconcile creates B's session
POLL_INTERVAL=15

STAGE="startup"
note() { echo "[nightly] $*"; }
set_stage() { STAGE="$1"; echo; note "== $1 =="; }

# gh uses the sandbox App token for every call.
export GH_TOKEN="$SANDBOX_TOKEN"

# --- Worker lifecycle: guaranteed teardown on any exit -------------------------------------------
WORKER_PID=""
teardown() {
  local ec=$?
  if [ -n "$WORKER_PID" ] && kill -0 "$WORKER_PID" 2>/dev/null; then
    note "stopping worker (SIGTERM, pid=$WORKER_PID)"
    kill -TERM "$WORKER_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$WORKER_PID" 2>/dev/null || break; sleep 1; done
    if kill -0 "$WORKER_PID" 2>/dev/null; then
      note "worker did not stop in 20s; SIGKILL"
      kill -KILL "$WORKER_PID" 2>/dev/null || true
    fi
  fi
  exit "$ec"
}
trap teardown EXIT

die() {
  note "FAILURE at stage: $STAGE"
  note "$*"
  echo
  note "---- worker log (tail) ----"
  tail -n 60 "$WORKER_LOG" 2>/dev/null || note "(no worker log)"
  note "---------------------------"
  echo "LOOP_RESULT fixture=gradle outcome=fail retried=false stage=$STAGE"
  exit 1
}

# --- Auth: mint a worker-SA ID token for the assertion calls (same pattern as smoke/smoke.sh) -----
note "minting a worker-SA ID token for assertions (impersonating $WORKER_SA_EMAIL, aud=$API_URL)"
TOKEN="$(
  gcloud auth print-identity-token \
    --impersonate-service-account="$WORKER_SA_EMAIL" \
    --audiences="$API_URL" \
    --include-email | tr -d '[:space:]'
)"
[ -n "$TOKEN" ] || die "could not mint an assertion ID token as $WORKER_SA_EMAIL (see the gcloud error above)"

grpc() {
  curl -s --http1.1 -X POST "$API_URL/$1" \
    -H "authorization: Bearer $TOKEN" \
    -H "content-type: application/json" \
    -d "${2:-\{\}}"
}

# Extract a field from THIS run's session for a given issue number (proto3 JSON = lowerCamelCase),
# looking it up by (repoFullName, issueNumber) so historical sessions never match. Empty if none yet.
session_field() { # $1=issue_number  $2=python expr over the session dict `s`
  grpc "medusa.session.v1.SessionService/ListSessions" '{}' | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
iss = $1
ss = [s for s in d.get('sessions', [])
      if s.get('repoFullName') == '$FULL' and int(s.get('issueNumber', 0) or 0) == iss]
if ss:
    s = ss[0]
    print($2)
" 2>/dev/null
}

# --- Generic wait: poll a predicate until true, aborting early if the worker dies ----------------
await() { # $1=description  $2=timeout_s  $3=predicate fn  $4..=args
  local desc="$1" timeout="$2" fn="$3"; shift 3
  local start=$SECONDS
  note "waiting for: $desc (timeout ${timeout}s)"
  while true; do
    if "$fn" "$@"; then note "  OK — $desc"; return 0; fi
    if ! kill -0 "$WORKER_PID" 2>/dev/null; then
      die "the worker process exited while waiting for: $desc"
    fi
    if [ $((SECONDS - start)) -ge "$timeout" ]; then
      die "timed out after ${timeout}s waiting for: $desc"
    fi
    sleep "$POLL_INTERVAL"
  done
}
have_session()      { [ -n "$(session_field "$1" "s['id']")" ]; }
session_completed() { [ "$(session_field "$1" "s['state']")" = "SESSION_STATE_COMPLETED" ]; }
session_failed()    { [ "$(session_field "$1" "s['state']")" = "SESSION_STATE_FAILED" ]; }
pr_merged()         { [ "$(gh pr view "$1" --repo "$FULL" --json merged --jq .merged 2>/dev/null)" = "true" ]; }
issue_closed()      { [ "$(gh issue view "$1" --repo "$FULL" --json state --jq .state 2>/dev/null)" = "CLOSED" ]; }

# =================================================================================================
# 1. Reset the sandbox fixtures to their canonical state.
# =================================================================================================
set_stage "reset sandbox fixtures"
GH_TOKEN="$SANDBOX_TOKEN" "$REPO_ROOT/sandbox/seed.sh" || die "sandbox/seed.sh failed"

# =================================================================================================
# 2. Identify this run's ready issues: A (unblocked) and B (blocked-by A).
# =================================================================================================
set_stage "identify the ready-issue chain"
issues_json="$(gh issue list --repo "$FULL" --state open --label "flow:ready" --json number,title)"
ISSUE_A="$(echo "$issues_json" | python3 -c "import json,sys; xs=json.load(sys.stdin); print(next((i['number'] for i in xs if 'greeting' in i['title'].lower()), ''))")"
ISSUE_B="$(echo "$issues_json" | python3 -c "import json,sys; xs=json.load(sys.stdin); print(next((i['number'] for i in xs if 'farewell' in i['title'].lower()), ''))")"
[ -n "$ISSUE_A" ] && [ -n "$ISSUE_B" ] || die "could not identify issues A/B from: $issues_json"
note "issue A=#$ISSUE_A (unblocked), issue B=#$ISSUE_B (blocked by A)"

# =================================================================================================
# 3. Start the real worker in the runner (production wiring: real auth, real GitHub, real models).
# =================================================================================================
set_stage "start the worker"
: > "$WORKER_LOG"
GOOGLE_APPLICATION_CREDENTIALS="$WORKER_ADC_FILE" \
FLOW_API_URL="$API_URL" \
FLOW_WORKER_GITHUB_TOKEN="$SANDBOX_TOKEN" \
OPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
  java --enable-native-access=ALL-UNNAMED -jar "$CLI_JAR" work >>"$WORKER_LOG" 2>&1 &
WORKER_PID=$!
note "worker started (pid=$WORKER_PID), logging to $WORKER_LOG"
sleep 5
kill -0 "$WORKER_PID" 2>/dev/null || die "the worker exited immediately on startup"

# =================================================================================================
# 4. Assert the full loop, driven autonomously by staging's reconcile scheduler.
# =================================================================================================
set_stage "reconcile creates a session for issue A"
await "a session for issue #$ISSUE_A" "$SESSION_CREATE_TIMEOUT" have_session "$ISSUE_A"

set_stage "worker completes session A and opens a PR"
# The AI leg. A FAILED session (the worker gave up) is a definitive negative — surface it at once
# with its summary rather than waiting out the whole timeout.
await_session_a_start=$SECONDS
while true; do
  if session_completed "$ISSUE_A"; then note "  OK — session A COMPLETED"; break; fi
  if session_failed "$ISSUE_A"; then
    die "session A FAILED: $(session_field "$ISSUE_A" "s.get('failureSummary','(no summary)')")"
  fi
  kill -0 "$WORKER_PID" 2>/dev/null || die "the worker exited while running session A"
  [ $((SECONDS - await_session_a_start)) -ge "$SESSION_COMPLETE_TIMEOUT" ] \
    && die "timed out (${SESSION_COMPLETE_TIMEOUT}s) waiting for session A to complete"
  sleep "$POLL_INTERVAL"
done

PR_URL="$(session_field "$ISSUE_A" "s.get('prUrl','')")"
[ -n "$PR_URL" ] || die "session A completed but carries no prUrl"
PR_NUM="$(echo "$PR_URL" | grep -oE '[0-9]+$')"
[ -n "$PR_NUM" ] || die "could not parse a PR number from prUrl=$PR_URL"
note "PR opened: $PR_URL (#$PR_NUM)"

set_stage "control plane merges the PR"
await "PR #$PR_NUM to be merged" "$PR_MERGE_TIMEOUT" pr_merged "$PR_NUM"

set_stage "control plane closes issue A"
await "issue #$ISSUE_A to be closed" "$ISSUE_CLOSE_TIMEOUT" issue_closed "$ISSUE_A"

set_stage "blocked dependent (issue B) is picked"
await "a session for the now-unblocked issue #$ISSUE_B" "$DEPENDENT_TIMEOUT" have_session "$ISSUE_B"

# =================================================================================================
# 5. Verdict.
# =================================================================================================
set_stage "done"
note "the full real-stack loop completed: A implemented+merged+closed, B picked"
echo "LOOP_RESULT fixture=gradle outcome=pass retried=false stage=done"
