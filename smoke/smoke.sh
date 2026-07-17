#!/usr/bin/env bash
#
# Post-deploy smoke suite — closes the "deploy green ≠ app works" gap. A scripted, authenticated
# pass against a freshly deployed API + SPA: exercise the real request surface end to end, so a
# deploy that is up but broken (bad env var, unreachable DB, wrong OAuth client) fails here and
# blocks promotion, instead of being discovered in prod.
#
# It authenticates as a *worker* would: an impersonated worker-SA ID token, audience = the API URL.
# One identity suffices — that token passes the auth decorator and is in both the worker and
# reconcile allowlists (SessionService needs only a valid token). Runnable ad hoc against any
# environment, and wired as the gate between staging and prod deploys (story 07).
#
# Inputs (env):
#   API_URL          e.g. https://api.flow-baseline-staging.medusa.software   (required)
#   WEB_URL          e.g. https://flow-baseline-staging.medusa.software        (default: API_URL sans "api.")
#   WORKER_SA_EMAIL  the worker SA to impersonate                              (required)
#
# Requires: gcloud (authenticated with permission to impersonate WORKER_SA_EMAIL), curl, python3.

set -uo pipefail

API_URL="${API_URL:?API_URL is required}"
WORKER_SA_EMAIL="${WORKER_SA_EMAIL:?WORKER_SA_EMAIL is required}"
WEB_URL="${WEB_URL:-${API_URL/https:\/\/api./https://}}"

failures=0
pass() { echo "  ✅ $*"; }
fail() {
  echo "  ❌ $*"
  failures=$((failures + 1))
}

# --- Auth: mint a worker-audience ID token by impersonating the worker SA ---
# 2>/dev/null: gcloud prints an impersonation notice to stderr that would otherwise corrupt the
# token; tr strips any stray whitespace.
echo "Minting a worker-SA ID token (impersonating $WORKER_SA_EMAIL, aud=$API_URL)"
TOKEN="$(
  gcloud auth print-identity-token \
    --impersonate-service-account="$WORKER_SA_EMAIL" \
    --audiences="$API_URL" \
    --include-email 2>/dev/null | tr -d '[:space:]'
)"
[ -n "$TOKEN" ] || {
  echo "FATAL: could not mint an ID token as $WORKER_SA_EMAIL"
  exit 1
}

# --- Helpers ---

# A gRPC method called as unframed JSON over HTTP/1.1 (Armeria enableUnframedRequests). Prints the
# response body; a gRPC error body carries "grpc-code" (checked by callers).
grpc() {
  curl -s --http1.1 \
    -X POST "$API_URL/$1" \
    -H "authorization: Bearer $TOKEN" \
    -H "content-type: application/json" \
    -d "${2:-\{\}}"
}

http_code() { curl -s --http1.1 -o /dev/null -w "%{http_code}" "$@"; }

jq_get() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

is_grpc_error() { grep -q '"grpc-code"'; }

# --- Checks ---

echo "== 1. health =="
if [ "$(http_code "$API_URL/health")" = "200" ]; then pass "GET /health -> 200"; else fail "GET /health not 200"; fi

echo "== 2. session round-trip (CreateSession -> GetSession -> ListSessions) =="
create_body="$(grpc "medusa.session.v1.SessionService/CreateSession" \
  '{"repoFullName":"smoke/test","taskMarkdown":"# Smoke test session (safe to ignore)"}')"
session_id="$(echo "$create_body" | jq_get "d['session']['id']")"
if [ -n "$session_id" ]; then pass "CreateSession -> $session_id"; else fail "CreateSession failed: $create_body"; fi

if [ -n "$session_id" ]; then
  state="$(grpc "medusa.session.v1.SessionService/GetSession" "{\"id\":\"$session_id\"}" | jq_get "d['session']['state']")"
  [ "$state" = "SESSION_STATE_PENDING" ] && pass "GetSession -> PENDING" || fail "GetSession state=$state"

  if grpc "medusa.session.v1.SessionService/ListSessions" '{}' \
    | jq_get "'$session_id' in [s['id'] for s in d.get('sessions',[])]" | grep -q True; then
    pass "ListSessions includes the session"
  else
    fail "ListSessions does not include $session_id"
  fi
fi

echo "== 3. worker cycle (claim -> heartbeat -> fail), draining the queue clean =="
claimed=0
for _ in $(seq 1 20); do
  claim_body="$(grpc "medusa.session.v1.WorkerService/ClaimNextSession" '{}')"
  cid="$(echo "$claim_body" | jq_get "d.get('session',{}).get('id','')")"
  [ -z "$cid" ] && break
  claimed=$((claimed + 1))
  grpc "medusa.session.v1.WorkerService/Heartbeat" "{\"sessionId\":\"$cid\"}" | is_grpc_error && fail "Heartbeat($cid) errored"
  grpc "medusa.session.v1.WorkerService/FailSession" "{\"sessionId\":\"$cid\",\"failureSummary\":\"post-deploy smoke; safe to ignore\"}" | is_grpc_error && fail "FailSession($cid) errored"
done
[ "$claimed" -ge 1 ] && pass "claimed + heartbeat + failed $claimed session(s); queue drained" || fail "ClaimNextSession returned nothing to cycle"

echo "== 4. reconcile returns sanely (out-of-fence repo: no side effects) =="
# A repo outside the reconciler's org fence: the call must succeed and do nothing.
reconcile_body="$(grpc "medusa.pipeline.v1.ReconcileService/Reconcile" '{"repoFullName":"smoke/not-in-any-fenced-org"}')"
echo "$reconcile_body" | is_grpc_error && fail "Reconcile errored: $reconcile_body" || pass "Reconcile -> ok"

echo "== 5. webhook rejects a bad signature =="
code="$(http_code -X POST "$API_URL/webhook/github" -H "x-hub-signature-256: sha256=deadbeef" -H "content-type: application/json" -d '{}')"
[ "$code" = "401" ] && pass "POST /webhook/github (bad sig) -> 401" || fail "webhook bad-sig returned $code, expected 401"

echo "== 6. SPA serves =="
# IAP-gated, so an unauthenticated GET is redirected to the login screen (302), not the SPA itself;
# any non-5xx proves the service is up and reachable.
code="$(http_code "$WEB_URL")"
if [ "$code" -ge 200 ] 2>/dev/null && [ "$code" -lt 500 ]; then pass "GET $WEB_URL -> $code (reachable)"; else fail "SPA returned $code"; fi

# --- Verdict ---
echo
if [ "$failures" -eq 0 ]; then
  echo "SMOKE PASSED"
else
  echo "SMOKE FAILED: $failures check(s) failed"
fi
exit "$failures"
