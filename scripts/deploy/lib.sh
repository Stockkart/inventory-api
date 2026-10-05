#!/usr/bin/env bash
# Shared helpers for the deploy scripts. Source this file; do not execute it.
#
#   source "$(dirname "$0")/lib.sh"
#
# Every script that sources this runs with `set -euo pipefail` and reads its
# inputs from environment variables named exactly like the GitHub secrets /
# variables, so the same script works from a laptop and from a workflow.

set -euo pipefail

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

log() { printf '%s [deploy] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

die() {
  printf '%s [deploy] ERROR: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2
  exit 1
}

# Emit a GitHub Actions output (key=value) when running in Actions; otherwise
# print it so a human sees the same information.
emit_output() {
  local key=$1 value=$2
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$key" "$value" >>"$GITHUB_OUTPUT"
  fi
  log "$key=$value"
}

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------

# require_env NAME [NAME...] — fail with one message listing every missing var.
require_env() {
  local missing=()
  local name
  for name in "$@"; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  ((${#missing[@]} == 0)) || die "missing required environment variable(s): ${missing[*]}"
}

# require_cmd NAME [NAME...]
require_cmd() {
  local missing=()
  local name
  for name in "$@"; do
    command -v "$name" >/dev/null 2>&1 || missing+=("$name")
  done
  ((${#missing[@]} == 0)) || die "missing required command(s): ${missing[*]}"
}

# require_sha VALUE — a full 40-hex-char git commit hash.
require_sha() {
  [[ "${1:-}" =~ ^[0-9a-f]{40}$ ]] || die "expected a full 40-character commit sha, got '${1:-}'"
}

# ---------------------------------------------------------------------------
# Polling
# ---------------------------------------------------------------------------

# poll INTERVAL_SECONDS TIMEOUT_MINUTES FUNCTION [ARGS...]
#
# Calls FUNCTION repeatedly until it returns 0 (done) or 2 (terminal failure).
# Return 1 from FUNCTION to mean "not yet". Exits non-zero on timeout with the
# elapsed time in the message.
poll() {
  local interval=$1 timeout_min=$2
  shift 2
  local deadline=$((SECONDS + timeout_min * 60))
  local rc
  while :; do
    rc=0
    "$@" || rc=$?
    case $rc in
      0) return 0 ;;
      2) return 2 ;;
    esac
    if ((SECONDS >= deadline)); then
      log "timed out after ${timeout_min}m waiting for: $*"
      return 3
    fi
    sleep "$interval"
  done
}

# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

# http_json METHOD URL [CURL_ARGS...]
# Sets HTTP_BODY (response body) and HTTP_STATUS (code). Must be called
# directly, not inside $(...), or the variables are lost to a subshell. Never
# fails on non-2xx (callers decide); if curl cannot connect, HTTP_STATUS is 000.
HTTP_STATUS=000
HTTP_BODY=""
http_json() {
  local method=$1 url=$2
  shift 2
  local body_file
  body_file=$(mktemp)
  HTTP_STATUS=$(curl --silent --show-error --location \
    --request "$method" "$url" \
    --header 'Accept: application/json' \
    --output "$body_file" --write-out '%{http_code}' \
    --max-time 30 "$@" 2>/dev/null || echo 000)
  HTTP_BODY=$(cat "$body_file")
  rm -f "$body_file"
}

is_2xx() { [[ "$HTTP_STATUS" =~ ^2 ]]; }

# api_error_message — best-effort human message from HTTP_BODY ({"message": ...}) or the raw body.
api_error_message() {
  jq -r '.message // .error // .' <<<"$HTTP_BODY" 2>/dev/null || printf '%s' "$HTTP_BODY"
}
