#!/usr/bin/env bash
# Only an explicit GitHub HTTP 404 proves absence. Never turn API failures into permission to publish.
set -euo pipefail
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
: "${RELEASE_TAG:?RELEASE_TAG required}"
response=$(mktemp)
trap 'rm -f "$response"' EXIT
# -i puts the response headers (including the HTTP status line) on stdout, so the
# decision keys on the status the server actually returned. gh's human-readable
# error prose on stderr is never parsed: its wording is not an API contract.
if gh api -i "repos/$GITHUB_REPOSITORY/git/ref/tags/$RELEASE_TAG" >"$response"; then
  echo "tag $RELEASE_TAG already exists; refusing publication" >&2
  exit 1
fi
status_line=$(head -n 1 "$response")
if [[ "$status_line" =~ ^HTTP/[0-9.]+[[:space:]]+404([[:space:]]|$) ]]; then
  echo "GitHub explicitly returned HTTP 404: $RELEASE_TAG is absent"
else
  echo "Cannot establish tag absence; refusing publication" >&2
  cat "$response" >&2
  exit 1
fi
