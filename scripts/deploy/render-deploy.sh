#!/usr/bin/env bash
# Deploy a backend image tag to the Render staging service and wait until it is live.
#
# Usage:   scripts/deploy/render-deploy.sh <sha>
#
# Environment:
#   RENDER_API_KEY      Render API key (secret)
#   RENDER_SERVICE_ID   id of the image-backed web service, e.g. srv-abc123
#   IMAGE_REPOSITORY    optional, default docker.io/myntrack/inventory-backend
#   RENDER_TIMEOUT_MIN  optional, default 10
#
# Outputs (GITHUB_OUTPUT when in Actions, otherwise logged):
#   deploy_id, deploy_url, image
#
# Newest wins: any deploy still in progress on the service is cancelled first,
# so back-to-back pushes always end with the latest one live.
#
# Render API: https://api-docs.render.com/reference/create-deploy
# The service must be image-backed and already configured for IMAGE_REPOSITORY;
# the API rejects an imageUrl whose host/repository differ from the service's.

# shellcheck source=scripts/deploy/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SHA=${1:-}
require_sha "$SHA"
require_env RENDER_API_KEY RENDER_SERVICE_ID
require_cmd curl jq

IMAGE_REPOSITORY=${IMAGE_REPOSITORY:-docker.io/myntrack/inventory-backend}
RENDER_TIMEOUT_MIN=${RENDER_TIMEOUT_MIN:-10}
API=${RENDER_API_BASE:-https://api.render.com/v1}
IMAGE="${IMAGE_REPOSITORY}:${SHA}"

render_api() {
  http_json "$@" --header "Authorization: Bearer ${RENDER_API_KEY}"
}

# Statuses Render reports while a deploy is still running.
IN_PROGRESS_RE='^(created|queued|build_in_progress|pre_deploy_in_progress|update_in_progress)$'

# --- 0. newest wins: cancel anything still deploying -------------------------
# Staging must show the latest push. Cancelling is safe for users: Render keeps
# the current instance serving until a new one passes its health check.
cancel_in_progress_deploys() {
  local ids id
  render_api GET "${API}/services/${RENDER_SERVICE_ID}/deploys?limit=20"
  if ! is_2xx; then
    log "Render: could not list deploys (HTTP ${HTTP_STATUS}); continuing without cancelling"
    return 0
  fi
  ids=$(jq -r --arg re "$IN_PROGRESS_RE" '.[] | .deploy | select(.status | test($re)) | .id' <<<"$HTTP_BODY")
  [[ -n "$ids" ]] || return 0
  for id in $ids; do
    render_api POST "${API}/services/${RENDER_SERVICE_ID}/deploys/${id}/cancel"
    if is_2xx; then
      log "Render: cancelled in-progress deploy ${id} (superseded by ${SHA})"
    else
      log "Render: cancel of ${id} returned HTTP ${HTTP_STATUS} (probably just finished); continuing"
    fi
  done
}
cancel_in_progress_deploys

# --- 1. trigger -------------------------------------------------------------
log "Render: deploying ${IMAGE} to service ${RENDER_SERVICE_ID}"
body=$(jq -cn --arg img "$IMAGE" '{imageUrl: $img}')
render_api POST "${API}/services/${RENDER_SERVICE_ID}/deploys" \
  --header 'Content-Type: application/json' --data "$body"
is_2xx || die "Render refused the deploy (HTTP ${HTTP_STATUS}): $(api_error_message)"

DEPLOY_ID=$(jq -r '.id // empty' <<<"$HTTP_BODY")

if [[ -z "$DEPLOY_ID" ]]; then
  # HTTP 202: another deploy slipped in between our cancel and trigger, and the
  # workspace policy is "Wait" — Render queued ours without returning an id.
  # Find it by image tag.
  log "Render: deploy queued behind another (HTTP ${HTTP_STATUS}); locating it by image tag"
  find_queued_deploy() {
    render_api GET "${API}/services/${RENDER_SERVICE_ID}/deploys?limit=20"
    is_2xx || return 1
    DEPLOY_ID=$(jq -r --arg sha "$SHA" --arg re "$IN_PROGRESS_RE" \
      '[.[] | .deploy | select((.image.ref // "" | endswith(":" + $sha)) and (.status | test($re)))] | first | .id // empty' <<<"$HTTP_BODY")
    [[ -n "$DEPLOY_ID" ]]
  }
  poll 10 2 find_queued_deploy || die "Render accepted the deploy but no deploy for ${IMAGE} appeared within 2 minutes"
fi

DEPLOY_URL="https://dashboard.render.com/web/${RENDER_SERVICE_ID}/deploys/${DEPLOY_ID}"
log "Render: deploy ${DEPLOY_ID} created — ${DEPLOY_URL}"

# --- 2. wait ----------------------------------------------------------------
LAST_STATUS=""
check_deploy() {
  local status
  render_api GET "${API}/services/${RENDER_SERVICE_ID}/deploys/${DEPLOY_ID}"
  if ! is_2xx; then
    log "Render: status check returned HTTP ${HTTP_STATUS}, retrying"
    return 1
  fi
  status=$(jq -r '.status // "unknown"' <<<"$HTTP_BODY")
  if [[ "$status" != "$LAST_STATUS" ]]; then
    log "Render: deploy status = ${status}"
    LAST_STATUS=$status
  fi
  case $status in
    live) return 0 ;;
    canceled)
      log "Render: deploy ${DEPLOY_ID} was cancelled — superseded by a newer deploy, or cancelled in the dashboard. See ${DEPLOY_URL}"
      return 2
      ;;
    build_failed | update_failed | pre_deploy_failed | deactivated)
      log "Render: deploy ended in '${status}' — see ${DEPLOY_URL}"
      return 2
      ;;
    *) return 1 ;;
  esac
}

rc=0
poll 15 "$RENDER_TIMEOUT_MIN" check_deploy || rc=$?
case $rc in
  0) log "Render: deploy ${DEPLOY_ID} is live" ;;
  2)
    if [[ "$LAST_STATUS" == "canceled" ]]; then
      die "Render deploy was superseded (status 'canceled'); a newer deploy owns staging now"
    fi
    die "Render deploy failed (status '${LAST_STATUS}')"
    ;;
  *) die "Render deploy did not reach 'live' within ${RENDER_TIMEOUT_MIN} minutes (last status '${LAST_STATUS}') — ${DEPLOY_URL}" ;;
esac

emit_output deploy_id "$DEPLOY_ID"
emit_output deploy_url "$DEPLOY_URL"
emit_output image "$IMAGE"
