#!/usr/bin/env bash
# Prove that a backend environment is really running a given commit and is healthy.
#
# Usage:   scripts/deploy/verify-backend.sh <base-url> <sha> [timeout-minutes]
#
# Passes when, within the timeout:
#   GET <base>/actuator/health  returns {"status":"UP"}      and
#   GET <base>/commit.txt       returns exactly <sha>
# and then health stays UP for three further checks 20 s apart (catches a
# container that starts, reports UP once, and crash-loops).
#
# Connection errors and 5xx are treated as "not yet" until the timeout, because
# Render free instances cold-start and App Platform swaps containers mid-rollout.
#
# VERIFY_MODE=reachable drops the commit.txt check (health only) for images built
# from commits that predate /commit.txt. The caller decides the mode from the
# deployed commit's Dockerfile; this script does not guess.
# TODO(temporary-old-commit-deploys): remove the reachable mode (and every VERIFY_MODE branch
# below) once old commits no longer need deploying.
#
# Environment (optional): VERIFY_SETTLE_CHECKS (default 3), VERIFY_SETTLE_INTERVAL (default 20)

# shellcheck source=scripts/deploy/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

BASE=${1:-}
SHA=${2:-}
TIMEOUT_MIN=${3:-10}
[[ -n "$BASE" ]] || die "usage: verify-backend.sh <base-url> <sha> [timeout-minutes]"
require_sha "$SHA"
require_cmd curl jq
BASE=${BASE%/}
SETTLE_CHECKS=${VERIFY_SETTLE_CHECKS:-3}
SETTLE_INTERVAL=${VERIFY_SETTLE_INTERVAL:-20}
VERIFY_MODE=${VERIFY_MODE:-sha}
[[ "$VERIFY_MODE" == "sha" || "$VERIFY_MODE" == "reachable" ]] || die "VERIFY_MODE must be 'sha' or 'reachable', got '$VERIFY_MODE'"

health_up() {
  local status
  http_json GET "${BASE}/actuator/health"
  is_2xx || return 1
  status=$(jq -r '.status // empty' <<<"$HTTP_BODY" 2>/dev/null || true)
  [[ "$status" == "UP" ]]
}

LAST_COMMIT=""
commit_matches() {
  [[ "$VERIFY_MODE" == "sha" ]] || return 0
  http_json GET "${BASE}/commit.txt" --header 'Accept: text/plain'
  is_2xx || return 1
  LAST_COMMIT=$(tr -d '[:space:]' <<<"$HTTP_BODY")
  [[ "$LAST_COMMIT" == "$SHA" ]]
}

LAST_REASON=""
live_with_sha() {
  if ! health_up; then
    LAST_REASON="health not UP (HTTP ${HTTP_STATUS})"
    return 1
  fi
  if ! commit_matches; then
    LAST_REASON="commit.txt is '${LAST_COMMIT:-<unreadable, HTTP ${HTTP_STATUS}>}', expected '${SHA}'"
    return 1
  fi
  return 0
}

if [[ "$VERIFY_MODE" == "reachable" ]]; then
  log "verify: ${SHA} predates /commit.txt; waiting up to ${TIMEOUT_MIN}m for ${BASE} health to be UP"
else
  log "verify: waiting up to ${TIMEOUT_MIN}m for ${BASE} to serve ${SHA}"
fi
poll 15 "$TIMEOUT_MIN" live_with_sha || die "verification failed after ${TIMEOUT_MIN}m: ${LAST_REASON}"
if [[ "$VERIFY_MODE" == "reachable" ]]; then
  log "verify: health UP (which commit runs cannot be proven for this build)"
else
  log "verify: health UP and commit.txt == ${SHA}"
fi

for ((i = 1; i <= SETTLE_CHECKS; i++)); do
  sleep "$SETTLE_INTERVAL"
  health_up || die "verification failed: health dropped to not-UP (HTTP ${HTTP_STATUS}) on settle check ${i}/${SETTLE_CHECKS}"
  commit_matches || die "verification failed: commit.txt changed to '${LAST_COMMIT}' on settle check ${i}/${SETTLE_CHECKS} (another deploy running?)"
  log "verify: settle check ${i}/${SETTLE_CHECKS} ok"
done

if [[ "$VERIFY_MODE" == "reachable" ]]; then
  log "verify: ${BASE} is healthy"
  emit_output verified_sha ""
else
  log "verify: ${BASE} is live on ${SHA}"
  emit_output verified_sha "$SHA"
fi
