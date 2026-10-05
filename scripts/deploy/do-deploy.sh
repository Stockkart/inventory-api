#!/usr/bin/env bash
# Deploy a backend image tag to the DigitalOcean App Platform production app.
#
# Usage:   scripts/deploy/do-deploy.sh <sha>
#
# Environment:
#   DIGITALOCEAN_TOKEN   API token with Apps read/write (secret)
#   DO_APP_ID            App Platform app id
#   DO_IMAGE_REPOSITORY  optional, image repository name in the spec, default inventory-backend
#   DO_TIMEOUT_MIN       optional, default 15
#
# Outputs: deployment_id, deployment_url, previous_tag, image
#
# How it works (see .do/README.md for why the spec is not committed):
#   1. GET the app's current spec from DigitalOcean.
#   2. Change ONLY the image tag of the backend service to <sha>.
#   3. PUT the spec back; App Platform starts a deployment. If the tag was
#      already <sha> (re-deploy of the same build) a deployment is created
#      explicitly instead.
#   4. Poll the deployment until ACTIVE, or fail with the platform's error.
# API: https://docs.digitalocean.com/reference/api/digitalocean/#tag/Apps

# shellcheck source=scripts/deploy/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SHA=${1:-}
require_sha "$SHA"
require_env DIGITALOCEAN_TOKEN DO_APP_ID
require_cmd curl jq

DO_IMAGE_REPOSITORY=${DO_IMAGE_REPOSITORY:-inventory-backend}
DO_TIMEOUT_MIN=${DO_TIMEOUT_MIN:-15}
API=${DO_API_BASE:-https://api.digitalocean.com/v2}

do_api() {
  http_json "$@" \
    --header "Authorization: Bearer ${DIGITALOCEAN_TOKEN}" \
    --header 'Content-Type: application/json'
}

fail_with_api_error() {
  # $1 = context; uses HTTP_STATUS / HTTP_BODY from the last call
  die "$1 (HTTP ${HTTP_STATUS}): $(api_error_message)"
}

# --- 1. fetch current spec -------------------------------------------------
log "DigitalOcean: fetching app ${DO_APP_ID}"
do_api GET "${API}/apps/${DO_APP_ID}"
is_2xx || fail_with_api_error "could not fetch app"

spec=$(jq -c '.app.spec' <<<"$HTTP_BODY")
[[ "$spec" != "null" ]] || die "app ${DO_APP_ID} has no spec in the API response"

matches=$(jq --arg repo "$DO_IMAGE_REPOSITORY" \
  '[.services[]? | select(.image.repository == $repo)] | length' <<<"$spec")
((matches == 1)) || die "expected exactly one service with image.repository='${DO_IMAGE_REPOSITORY}' in the app spec, found ${matches}"

PREVIOUS_TAG=$(jq -r --arg repo "$DO_IMAGE_REPOSITORY" \
  '.services[] | select(.image.repository == $repo) | .image.tag // ""' <<<"$spec")
registry=$(jq -r --arg repo "$DO_IMAGE_REPOSITORY" \
  '.services[] | select(.image.repository == $repo) | .image.registry // ""' <<<"$spec")
IMAGE="${registry:+${registry}/}${DO_IMAGE_REPOSITORY}:${SHA}"
log "DigitalOcean: current tag '${PREVIOUS_TAG:-<none>}' → '${SHA}'"

# --- 2. patch only the tag --------------------------------------------------
patched=$(jq -c --arg repo "$DO_IMAGE_REPOSITORY" --arg sha "$SHA" \
  '.services |= map(if .image.repository == $repo then .image.tag = $sha else . end)' <<<"$spec")

# Sanity: nothing but the tag changed.
diff_count=$(jq -n --argjson a "$spec" --argjson b "$patched" --arg repo "$DO_IMAGE_REPOSITORY" '
  ($a | .services |= map(if .image.repository == $repo then .image.tag = "X" else . end)) as $na
  | ($b | .services |= map(if .image.repository == $repo then .image.tag = "X" else . end)) as $nb
  | if $na == $nb then 0 else 1 end')
((diff_count == 0)) || die "internal error: patched spec differs from the original in more than the image tag"

# --- 3. update app (creates a deployment) -----------------------------------
log "DigitalOcean: updating app spec"
do_api PUT "${API}/apps/${DO_APP_ID}" --data "$(jq -cn --argjson spec "$patched" '{spec: $spec}')"
is_2xx || fail_with_api_error "could not update app spec"

DEPLOYMENT_ID=$(jq -r '.app.pending_deployment.id // .app.in_progress_deployment.id // empty' <<<"$HTTP_BODY")

if [[ -z "$DEPLOYMENT_ID" ]]; then
  # Spec unchanged (same tag as before): ask for an explicit deployment so the
  # new build is still rolled out / restarted.
  log "DigitalOcean: spec unchanged, creating deployment explicitly"
  do_api POST "${API}/apps/${DO_APP_ID}/deployments" --data '{"force_build":false}'
  is_2xx || fail_with_api_error "could not create deployment"
  DEPLOYMENT_ID=$(jq -r '.deployment.id // empty' <<<"$HTTP_BODY")
fi
[[ -n "$DEPLOYMENT_ID" ]] || die "DigitalOcean did not return a deployment id"

DEPLOYMENT_URL="https://cloud.digitalocean.com/apps/${DO_APP_ID}/deployments/${DEPLOYMENT_ID}"
log "DigitalOcean: deployment ${DEPLOYMENT_ID} started — ${DEPLOYMENT_URL}"

# --- 4. wait ----------------------------------------------------------------
LAST_PHASE=""
LAST_ERROR=""
check_deployment() {
  local phase
  do_api GET "${API}/apps/${DO_APP_ID}/deployments/${DEPLOYMENT_ID}"
  if ! is_2xx; then
    log "DigitalOcean: status check returned HTTP ${HTTP_STATUS}, retrying"
    return 1
  fi
  phase=$(jq -r '.deployment.phase // "UNKNOWN"' <<<"$HTTP_BODY")
  if [[ "$phase" != "$LAST_PHASE" ]]; then
    log "DigitalOcean: deployment phase = ${phase}"
    LAST_PHASE=$phase
  fi
  case $phase in
    ACTIVE) return 0 ;;
    ERROR | CANCELED | SUPERSEDED)
      LAST_ERROR=$(jq -r '[.deployment.progress.steps[]? | .. | objects | select(.status? == "ERROR") | (.name // "step") + ": " + (.reason.message // .reason.code // "unknown")] | unique | join("; ")' <<<"$HTTP_BODY")
      return 2
      ;;
    *) return 1 ;;
  esac
}

rc=0
poll 15 "$DO_TIMEOUT_MIN" check_deployment || rc=$?
case $rc in
  0) log "DigitalOcean: deployment ${DEPLOYMENT_ID} is ACTIVE" ;;
  2) die "DigitalOcean deployment ended in '${LAST_PHASE}'${LAST_ERROR:+ — ${LAST_ERROR}} — ${DEPLOYMENT_URL}" ;;
  *) die "DigitalOcean deployment did not become ACTIVE within ${DO_TIMEOUT_MIN} minutes (last phase '${LAST_PHASE}') — ${DEPLOYMENT_URL}" ;;
esac

emit_output deployment_id "$DEPLOYMENT_ID"
emit_output deployment_url "$DEPLOYMENT_URL"
emit_output previous_tag "$PREVIOUS_TAG"
emit_output image "$IMAGE"
