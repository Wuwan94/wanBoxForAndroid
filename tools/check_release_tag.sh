#!/usr/bin/env bash
# Only an explicit GitHub HTTP 404 proves absence. Never turn API failures into permission to publish.
set -euo pipefail
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
: "${RELEASE_TAG:?RELEASE_TAG required}"
error=$(mktemp)
trap 'rm -f "$error"' EXIT
if gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$RELEASE_TAG" >/dev/null 2>"$error"; then
  echo "tag $RELEASE_TAG already exists; refusing publication" >&2
  exit 1
fi
# gh's REST error format includes the server HTTP status. A network/DNS error has no such status.
if grep -Eq '^gh: .*\(HTTP 404\)$' "$error"; then
  echo "GitHub explicitly returned HTTP 404: $RELEASE_TAG is absent"
else
  echo "Cannot establish tag absence; refusing publication" >&2
  cat "$error" >&2
  exit 1
fi
