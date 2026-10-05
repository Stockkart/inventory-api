#!/usr/bin/env bash
# Append a deployment summary table to the GitHub job summary (or stdout locally).
#
# Usage:   scripts/deploy/summary.sh
#
# Environment (all optional except SUMMARY_ENVIRONMENT, SUMMARY_SHA, SUMMARY_RESULT):
#   SUMMARY_ENVIRONMENT     staging | production
#   SUMMARY_SHA             deployed commit
#   SUMMARY_PREVIOUS        previously deployed commit/tag (for rollback)
#   SUMMARY_RESULT          success | failed
#   SUMMARY_DEPLOYMENT_ID   platform deployment id
#   SUMMARY_DEPLOYMENT_URL  platform dashboard link
#   SUMMARY_BASE_URL        public URL of the environment
#   SUMMARY_ACTOR           who triggered the run
#   SUMMARY_REASON          promote reason, if any
#   SUMMARY_REPOSITORY      owner/repo, used to link the commit

# shellcheck source=scripts/deploy/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_env SUMMARY_ENVIRONMENT SUMMARY_SHA SUMMARY_RESULT

repo=${SUMMARY_REPOSITORY:-${GITHUB_REPOSITORY:-}}
short() { printf '%s' "${1:0:12}"; }
commit_link() {
  local sha=$1
  [[ -n "$sha" ]] || { printf '—'; return; }
  if [[ -n "$repo" && "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    # shellcheck disable=SC2016 # backticks are Markdown, not shell
    printf '[`%s`](https://github.com/%s/commit/%s)' "$(short "$sha")" "$repo" "$sha"
  else
    # shellcheck disable=SC2016
    printf '`%s`' "$sha"
  fi
}

icon="✅"
[[ "$SUMMARY_RESULT" == "success" ]] || icon="❌"

out=$(
  cat <<EOF
## ${icon} Backend → ${SUMMARY_ENVIRONMENT} — ${SUMMARY_RESULT}

| | |
|---|---|
| Environment | \`${SUMMARY_ENVIRONMENT}\` |
| Deployed sha | $(commit_link "$SUMMARY_SHA") |
| Previous sha | $(commit_link "${SUMMARY_PREVIOUS:-}") |
| Platform deployment | ${SUMMARY_DEPLOYMENT_URL:+[}${SUMMARY_DEPLOYMENT_ID:-—}${SUMMARY_DEPLOYMENT_URL:+](${SUMMARY_DEPLOYMENT_URL})} |
| URL | ${SUMMARY_BASE_URL:-—} |
| Triggered by | ${SUMMARY_ACTOR:-${GITHUB_ACTOR:-—}} |
| Reason | ${SUMMARY_REASON:-—} |
| Time (UTC) | $(date -u +'%Y-%m-%d %H:%M:%S') |
EOF
)

if [[ -n "${SUMMARY_PREVIOUS:-}" && "$SUMMARY_ENVIRONMENT" == "production" ]]; then
  out+=$'\n\n'"Rollback: run **promote-to-production** with \`sha=${SUMMARY_PREVIOUS}\`."
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf '%s\n\n' "$out" >>"$GITHUB_STEP_SUMMARY"
else
  printf '%s\n' "$out"
fi
